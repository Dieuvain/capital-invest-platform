package cd.capitalinvest.platform

import cd.capitalinvest.platform.payments.mobilemoney.HttpSignatureVerifier
import cd.capitalinvest.platform.payments.mobilemoney.IncomingNotification
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpSignatureVerifierTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val now = Instant.parse("2026-11-19T10:00:00Z")
    private val verifier = HttpSignatureVerifier(Clock.fixed(now, ZoneOffset.UTC), { if (it == "k1") keys.public else null })

    private fun notification(created: Long = now.epochSecond, der: Boolean = false, keyId: String = "k1", components: String = "\"@method\" \"@path\" \"content-digest\""): IncomingNotification {
        val body = """{"depositId":"x"}""".toByteArray()
        val digest = HttpSignatureVerifier.contentDigest(body, "sha-256")
        val params = """($components);alg="ecdsa-p256-sha256";keyid="$keyId";created=$created;expires=${created + 60}"""
        val lines = components.split(' ').map { it.trim('"') }.map {
            when (it) {
                "@method" -> "\"@method\": POST"
                "@path" -> "\"@path\": /hook"
                else -> "\"content-digest\": $digest"
            }
        }
        val base = (lines + "\"@signature-params\": $params").joinToString("\n")
        val sig = Signature.getInstance(if (der) "SHA256withECDSA" else "SHA256withECDSAinP1363Format").run {
            initSign(keys.private)
            update(base.toByteArray())
            sign()
        }
        return IncomingNotification(
            "POST", "api.test", "/hook",
            mapOf(
                "content-digest" to digest,
                "signature-input" to "sig-pp=$params",
                "signature" to "sig-pp=:${Base64.getEncoder().encodeToString(sig)}:",
            ),
            body,
        )
    }

    @Test
    fun `signature brute ou DER acceptee`() {
        assertTrue(verifier.verify(notification()).valid)
        assertTrue(verifier.verify(notification(der = true)).valid)
    }

    @Test
    fun `signature expiree, cle inconnue ou corps non couvert refuses`() {
        assertEquals("Signature expirée", verifier.verify(notification(created = now.epochSecond - 600)).reason)
        assertEquals("Clé inconnue : k2", verifier.verify(notification(keyId = "k2")).reason)
        assertFalse(verifier.verify(notification(components = "\"@method\" \"@path\"")).valid)
    }
}
