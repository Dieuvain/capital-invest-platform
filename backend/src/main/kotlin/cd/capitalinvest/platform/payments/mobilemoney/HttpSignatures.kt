package cd.capitalinvest.platform.payments.mobilemoney

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64

/**
 * Vérification des signatures de messages HTTP (RFC 9421), telles que pawaPay les envoie :
 *
 *   Signature-Input: sig-pp=("@method" "@authority" "@path" "signature-date" "content-digest" "content-type");alg="ecdsa-p256-sha256";keyid="…";created=…;expires=…
 *   Signature: sig-pp=:base64:
 *   Content-Digest: sha-512=:base64:
 *
 * Le corps est d'abord contrôlé contre Content-Digest, puis la signature couvre l'empreinte et les en-têtes listés.
 */
class HttpSignatureVerifier(
    private val clock: Clock,
    private val keys: (keyId: String) -> PublicKey?,
    private val skewSeconds: Long = 60,
) {
    data class Outcome(val valid: Boolean, val reason: String)

    fun verify(n: IncomingNotification, authorityOverride: String? = null): Outcome {
        val input = n.headers["signature-input"] ?: return Outcome(false, "Signature-Input absent")
        val signatureHeader = n.headers["signature"] ?: return Outcome(false, "Signature absent")
        val digestHeader = n.headers["content-digest"] ?: return Outcome(false, "Content-Digest absent")

        if (!digestMatches(digestHeader, n.body)) return Outcome(false, "Content-Digest ne correspond pas au corps")

        // On prend la première signature déclarée et la valeur portant le même libellé.
        val (label, params) = members(input).firstOrNull() ?: return Outcome(false, "Signature-Input illisible")
        val sigValue = members(signatureHeader).firstOrNull { it.first == label }?.second
            ?: return Outcome(false, "Pas de signature « $label »")
        val sigBytes = Regex("^:([A-Za-z0-9+/=]+):$").find(sigValue.trim())?.groupValues?.get(1)
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return Outcome(false, "Signature illisible")

        val listEnd = params.indexOf(')')
        if (!params.startsWith("(") || listEnd < 0) return Outcome(false, "Liste des composants illisible")
        val components = Regex("\"([^\"]+)\"").findAll(params.substring(1, listEnd)).map { it.groupValues[1] }.toList()
        val attributes = params.substring(listEnd + 1).split(';').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            k.trim() to v.trim().trim('"')
        }
        if ("content-digest" !in components) return Outcome(false, "Le corps n'est pas couvert par la signature")
        val alg = attributes["alg"]
        if (alg != null && alg != "ecdsa-p256-sha256") return Outcome(false, "Algorithme non accepté : $alg")

        val now = clock.instant().epochSecond
        attributes["expires"]?.toLongOrNull()?.let { if (now > it + skewSeconds) return Outcome(false, "Signature expirée") }
        attributes["created"]?.toLongOrNull()?.let { if (it > now + skewSeconds) return Outcome(false, "Signature datée du futur") }

        val keyId = attributes["keyid"] ?: return Outcome(false, "keyid absent")
        val key = keys(keyId) ?: return Outcome(false, "Clé inconnue : $keyId")

        val base = buildString {
            for (c in components) {
                val value = when (c) {
                    "@method" -> n.method.uppercase()
                    "@authority" -> (authorityOverride ?: n.authority).lowercase()
                    "@path" -> n.path
                    else -> n.headers[c.lowercase()]?.trim() ?: return Outcome(false, "En-tête signé absent : $c")
                }
                append('"').append(c).append("\": ").append(value).append('\n')
            }
            append("\"@signature-params\": ").append(params)
        }
        return if (ecdsaVerify(key, base.toByteArray(Charsets.UTF_8), sigBytes)) Outcome(true, "ok")
        else Outcome(false, "Signature invalide")
    }

    companion object {
        /** Lit une clé publique PEM (SubjectPublicKeyInfo), comme pawaPay la publie. */
        fun parsePublicKey(pem: String): PublicKey {
            val der = Base64.getMimeDecoder().decode(
                pem.replace(Regex("-----(BEGIN|END) PUBLIC KEY-----"), "").replace(Regex("\\s"), ""),
            )
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        }

        fun contentDigest(body: ByteArray, algorithm: String = "sha-512"): String {
            val md = MessageDigest.getInstance(if (algorithm == "sha-256") "SHA-256" else "SHA-512")
            return "$algorithm=:${Base64.getEncoder().encodeToString(md.digest(body))}:"
        }

        /** Signature brute r||s (64 octets) ou DER : les deux formes existent selon les bibliothèques. */
        fun ecdsaVerify(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = runCatching {
            val alg = if (signature.size == 64) "SHA256withECDSAinP1363Format" else "SHA256withECDSA"
            Signature.getInstance(alg).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

        private fun digestMatches(header: String, body: ByteArray): Boolean {
            val known = members(header).filter { it.first == "sha-256" || it.first == "sha-512" }
            if (known.isEmpty()) return false
            return known.all { (alg, value) ->
                MessageDigest.isEqual(contentDigest(body, alg).toByteArray(), "$alg=${value.trim()}".toByteArray())
            }
        }

        /** Découpe un dictionnaire structuré « a=…, b=… » en respectant guillemets et parenthèses. */
        private fun members(header: String): List<Pair<String, String>> {
            val parts = mutableListOf<String>()
            var depth = 0
            var quoted = false
            val current = StringBuilder()
            for (ch in header) {
                when {
                    ch == '"' -> quoted = !quoted
                    !quoted && ch == '(' -> depth++
                    !quoted && ch == ')' -> depth--
                }
                if (ch == ',' && !quoted && depth == 0) {
                    parts += current.toString()
                    current.clear()
                } else {
                    current.append(ch)
                }
            }
            parts += current.toString()
            return parts.mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
            }
        }
    }
}
