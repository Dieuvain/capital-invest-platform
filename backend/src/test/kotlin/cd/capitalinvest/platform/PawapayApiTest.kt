package cd.capitalinvest.platform

import cd.capitalinvest.platform.payments.TopupService
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.post
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recharges M-Pesa et Airtel routées vers pawaPay, face à un faux serveur pawaPay.
 * Orange reste sur le simulateur : le routage se fait opérateur par opérateur.
 */
class PawapayApiTest : ApiTest() {

    /** Faux pawaPay : enregistre les demandes, répond les statuts qu'on lui fixe, publie sa clé publique. */
    object FakePawapay {
        val requests = mutableListOf<Pair<String, String>>()
        val authorizations = mutableListOf<String?>()
        val deposits = ConcurrentHashMap<String, String>()
        @Volatile var initiateStatus = 200
        @Volatile var initiateBody: (String) -> String = { id -> """{"depositId":"$id","status":"ACCEPTED"}""" }

        fun ec(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key: KeyPair = ec()

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v2/") { ex ->
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val path = ex.requestURI.path
                synchronized(requests) {
                    requests += "${ex.requestMethod} $path" to body
                    authorizations += ex.requestHeaders.getFirst("Authorization")
                }
                val (status, response) = when {
                    ex.requestMethod == "POST" && (path == "/v2/deposits" || path == "/v2/payouts") -> {
                        val sent = ObjectMapper().readTree(body)
                        val id = (sent["depositId"] ?: sent["payoutId"]).asText()
                        initiateStatus to initiateBody(id)
                    }
                    path == "/v2/public-key/http" -> {
                        val pem = "-----BEGIN PUBLIC KEY-----\n" +
                            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.public.encoded) +
                            "\n-----END PUBLIC KEY-----"
                        200 to ObjectMapper().writeValueAsString(listOf(mapOf("id" to "HTTP_EC_P256_KEY:1", "key" to pem)))
                    }
                    path.startsWith("/v2/deposits/") || path.startsWith("/v2/payouts/") -> {
                        val data = deposits[path.substringAfterLast('/')]
                        200 to if (data == null) """{"status":"NOT_FOUND"}""" else """{"status":"FOUND","data":$data}"""
                    }
                    else -> 404 to "{}"
                }
                val bytes = response.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun pawapay(registry: DynamicPropertyRegistry) {
            registry.add("capital-invest.mobile-money.routes.MPESA") { "PAWAPAY" }
            registry.add("capital-invest.mobile-money.routes.AIRTEL") { "PAWAPAY" }
            registry.add("capital-invest.mobile-money.pawapay.base-url") { "http://127.0.0.1:${FakePawapay.server.address.port}" }
            registry.add("capital-invest.mobile-money.pawapay.api-token") { "test-token" }
            registry.add("capital-invest.mobile-money.pawapay.callback-authority") { "api.capitalinvest.test" }
        }
    }

    @Autowired lateinit var topupService: TopupService

    @BeforeEach
    fun resetFake() {
        synchronized(FakePawapay.requests) {
            FakePawapay.requests.clear()
            FakePawapay.authorizations.clear()
        }
        FakePawapay.deposits.clear()
        FakePawapay.initiateStatus = 200
        FakePawapay.initiateBody = { id -> """{"depositId":"$id","status":"ACCEPTED"}""" }
    }

    private fun startTopup(s: Session, amount: Long, key: String, operator: String = "MPESA") =
        mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"$operator","amountMinor":$amount}"""
        }.andExpect { status { isAccepted() } }.body()

    /** Notification signée comme pawaPay le fait (RFC 9421), éventuellement avec une autre clé ou un corps modifié. */
    private fun callback(body: String, signWith: KeyPair? = FakePawapay.key, sentBody: String = body) =
        mvc.post("/v1/webhooks/mobile-money/pawapay") {
            // Avec le charset explicite : le filtre d'encodage de Spring l'ajouterait sinon à l'en-tête vu par MockMvc.
            header("Content-Type", "application/json;charset=UTF-8")
            content = sentBody
            if (signWith != null) {
                val digest = "sha-512=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(body.toByteArray())) + ":"
                val date = "2026-11-19T10:00:00Z"
                val now = testClock.instant().epochSecond
                val params = """("@method" "@authority" "@path" "signature-date" "content-digest" "content-type");alg="ecdsa-p256-sha256";keyid="HTTP_EC_P256_KEY:1";created=$now;expires=${now + 60}"""
                val base = listOf(
                    "\"@method\": POST",
                    "\"@authority\": api.capitalinvest.test",
                    "\"@path\": /v1/webhooks/mobile-money/pawapay",
                    "\"signature-date\": $date",
                    "\"content-digest\": $digest",
                    "\"content-type\": application/json;charset=UTF-8",
                    "\"@signature-params\": $params",
                ).joinToString("\n")
                val sig = Signature.getInstance("SHA256withECDSAinP1363Format").run {
                    initSign(signWith.private)
                    update(base.toByteArray())
                    sign()
                }
                header("Signature-Date", date)
                header("Content-Digest", digest)
                header("Signature-Input", "sig-pp=$params")
                header("Signature", "sig-pp=:${Base64.getEncoder().encodeToString(sig)}:")
            }
        }

    private fun completed(amount: String, ref: String = "MP-REF-1") =
        """{"status":"COMPLETED","amount":"$amount","currency":"USD","providerTransactionId":"$ref","country":"COD"}"""

    @Test
    fun `M-Pesa passe par pawaPay, et la notification signee credite apres relecture du statut`() {
        val alice = register("+243970000301")
        val topup = startTopup(alice, 1250, "pp-1")
        val id = topup["id"].asText()
        assertEquals("PENDING", topup["status"].asText())

        val (call, body) = FakePawapay.requests.single()
        assertEquals("POST /v2/deposits", call)
        val sent = json.readTree(body)
        assertEquals(id, sent["depositId"].asText())
        assertEquals("12.50", sent["amount"].asText())
        assertEquals("USD", sent["currency"].asText())
        assertEquals("243970000301", sent["payer"]["accountDetails"]["phoneNumber"].asText())
        assertEquals("VODACOM_MPESA_COD", sent["payer"]["accountDetails"]["provider"].asText())
        assertEquals("Bearer test-token", FakePawapay.authorizations.single())

        FakePawapay.deposits[id] = completed("12.50")
        val notification = """{"depositId":"$id","status":"COMPLETED","amount":"12.50","currency":"USD"}"""
        repeat(2) { callback(notification).andExpect { status { isOk() } } } // doublon : un seul crédit
        assertEquals(1250, balance(alice))

        val settlement = jdbc.sql(
            """SELECT a.code FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id
               JOIN topup t ON t.ledger_transaction_id = e.transaction_id
               WHERE t.id = :id::uuid AND e.direction = 'DEBIT'""",
        ).param("id", id).query(String::class.java).single()
        assertEquals("SETTLEMENT_PAWAPAY_USD", settlement)
        val signed = jdbc.sql("SELECT bool_and(signature_valid) FROM mobile_money_event").query(Boolean::class.java).single()
        assertTrue(signed)
    }

    @Test
    fun `une notification non signee, mal signee ou modifiee est refusee, et son contenu n'est jamais cru`() {
        val alice = register("+243970000302")
        val id = startTopup(alice, 2000, "pp-2", operator = "AIRTEL")["id"].asText()
        assertEquals("AIRTEL_COD", json.readTree(FakePawapay.requests.single().second)["payer"]["accountDetails"]["provider"].asText())
        val lie = """{"depositId":"$id","status":"COMPLETED","amount":"20.00","currency":"USD"}"""

        callback(lie, signWith = null).andExpect { status { isUnauthorized() } }
        callback(lie, signWith = FakePawapay.ec()).andExpect { status { isUnauthorized() } }
        callback(lie, sentBody = lie.replace("20.00", "200.00")).andExpect { status { isUnauthorized() } }

        // Bien signée, mais pawaPay dit que le paiement est encore en cours : on n'écoute que pawaPay.
        FakePawapay.deposits[id] = """{"status":"PROCESSING","amount":"20.00","currency":"USD"}"""
        callback(lie).andExpect { status { isOk() } }
        assertEquals("PENDING", topupService.get(UUID.fromString(id)).status.name)
        assertEquals(0, balance(alice))

        val outcomes = jdbc.sql("SELECT outcome FROM mobile_money_event ORDER BY received_at").query(String::class.java).list()
        assertEquals(listOf("REJECTED_SIGNATURE", "REJECTED_SIGNATURE", "REJECTED_SIGNATURE", "REFRESHED PENDING"), outcomes)
    }

    @Test
    fun `un refus de pawaPay echoue la recharge avec son motif`() {
        val alice = register("+243970000303")
        FakePawapay.initiateBody = { id ->
            """{"depositId":"$id","status":"REJECTED","failureReason":{"failureCode":"INVALID_PHONE_NUMBER","failureMessage":"Numéro inconnu chez l'opérateur"}}"""
        }
        startTopup(alice, 1000, "pp-3").let {
            assertEquals("FAILED", it["status"].asText())
            assertEquals("INVALID_PHONE_NUMBER", it["failureCode"].asText())
            assertEquals("Numéro inconnu chez l'opérateur", it["failureMessage"].asText())
        }
    }

    @Test
    fun `sans reponse a la demande, la recharge attend, puis echoue si pawaPay ne l'a jamais recue`() {
        val alice = register("+243970000304")
        FakePawapay.initiateStatus = 500
        FakePawapay.initiateBody = { """{"errorCode":"UNKNOWN_ERROR"}""" }
        val id = startTopup(alice, 1000, "pp-4")["id"].asText()
        assertEquals("PENDING", topupService.get(UUID.fromString(id)).status.name)

        testClock.advance(Duration.ofMinutes(5))
        topupService.checkDue()
        assertEquals("PENDING", topupService.get(UUID.fromString(id)).status.name, "trop tôt pour conclure")

        testClock.advance(Duration.ofMinutes(30))
        topupService.checkDue()
        val topup = topupService.get(UUID.fromString(id))
        assertEquals("FAILED", topup.status.name)
        assertEquals("NOT_RECEIVED", topup.failureCode)
    }

    @Test
    fun `un echec cote operateur est relu chez pawaPay et la relance automatique le constate`() {
        val alice = register("+243970000305")
        val id = startTopup(alice, 3000, "pp-5")["id"].asText()
        FakePawapay.deposits[id] =
            """{"status":"FAILED","amount":"30.00","currency":"USD","failureReason":{"failureCode":"INSUFFICIENT_BALANCE","failureMessage":"Solde M-Pesa insuffisant"}}"""
        testClock.advance(Duration.ofSeconds(11))
        assertEquals(1, topupService.checkDue())
        val topup = topupService.get(UUID.fromString(id))
        assertEquals("FAILED", topup.status.name)
        assertEquals("Solde M-Pesa insuffisant", topup.failureMessage)
    }

    @Test
    fun `un retrait part chez pawaPay, et un echec confirme rembourse le client`() {
        val alice = register("+243970000307")
        FakePawapay.deposits.clear()
        val top = startTopup(alice, 10000, "pp-7")["id"].asText()
        FakePawapay.deposits[top] = completed("100.00")
        testClock.advance(Duration.ofSeconds(11))
        topupService.checkDue()
        assertEquals(10000, balance(alice))

        val w = mvc.post("/v1/withdrawals") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "pp-w-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"AIRTEL","amountMinor":4000,"pin":"2580"}"""
        }.andExpect { status { isAccepted() } }.body()
        val id = w["id"].asText()
        val (call, body) = FakePawapay.requests.last()
        assertEquals("POST /v2/payouts", call)
        val sent = json.readTree(body)
        assertEquals(id, sent["payoutId"].asText())
        assertEquals("40.00", sent["amount"].asText())
        assertEquals("243970000307", sent["recipient"]["accountDetails"]["phoneNumber"].asText())
        assertEquals("AIRTEL_COD", sent["recipient"]["accountDetails"]["provider"].asText())
        assertEquals(10000 - 4000 - 60, balance(alice), "argent bloqué dès la demande")

        FakePawapay.deposits[id] = """{"status":"FAILED","failureReason":{"failureCode":"RECIPIENT_NOT_FOUND","failureMessage":"Unknown wallet"}}"""
        callback("""{"payoutId":"$id","status":"FAILED"}""").andExpect { status { isOk() } }
        assertEquals(10000, balance(alice), "montant et frais rendus")
    }

    @Test
    fun `Orange reste sur le simulateur`() {
        val alice = register("+243970000306")
        startTopup(alice, 500, "pp-6", operator = "ORANGE")
        assertTrue(FakePawapay.requests.isEmpty())
    }
}
