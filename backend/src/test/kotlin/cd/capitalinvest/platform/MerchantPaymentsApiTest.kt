package cd.capitalinvest.platform

import cd.capitalinvest.platform.auth.SimulatedSmsGateway
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paiement chez un marchand : QR en caisse, montant, PIN ; le marchand reçoit un SMS. */
class MerchantPaymentsApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var sms: SimulatedSmsGateway

    @BeforeEach
    fun clearSms() = sms.clear()

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun createMerchant(admin: String, name: String = "Alimentation Mama Nzuzi", phone: String = "0990000101") =
        mvc.post("/v1/backoffice/merchants") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name","category":"Alimentation","city":"Lubumbashi","phone":"$phone"}"""
        }

    private fun pay(s: Session, code: String, amountMinor: Long, key: String, pin: String = "2580") =
        mvc.post("/v1/merchant-payments") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"merchantCode":"$code","amountMinor":$amountMinor,"pin":"$pin"}"""
        }

    private fun lookup(s: Session, code: String) =
        mvc.get("/v1/merchants/${URLEncoder.encode(code, Charsets.UTF_8)}") { header("Authorization", "Bearer ${s.token}") }

    private fun history(s: Session, query: String = ""): JsonNode =
        mvc.get("/v1/me/history$query") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()

    @Test
    fun `le client scanne, paie sans frais, et le marchand recoit le montant moins sa commission et un SMS`() {
        val admin = agent(AgentRole.ADMIN)
        val merchant = createMerchant(admin).andExpect { status { isCreated() } }.body()
        val code = merchant["code"].asText()
        assertTrue(Regex("^[A-HJ-NP-Z2-9]{6}$").matches(code), code)
        assertEquals("capitalinvest://pay?m=$code", merchant["qrPayload"].asText())
        assertEquals("+243990000101", merchant["phone"].asText())
        assertEquals(100, merchant["commissionBps"].asInt())

        val alice = register("+243970000701")
        topupConfirmed(alice, 5000, "m-top-1")
        sms.clear()

        // Le code lu dans le QR, ou tapé à la main en minuscules avec des espaces.
        val shop = lookup(alice, code).andExpect { status { isOk() } }.body()
        assertEquals("Alimentation Mama Nzuzi", shop["name"].asText())
        lookup(alice, code.lowercase().chunked(3).joinToString(" ")).andExpect { status { isOk() } }
        lookup(alice, "ZZZZZZ").andExpect { status { isNotFound() } }

        val paid = pay(alice, merchant["qrPayload"].asText(), 1250, "m-pay-1").andExpect { status { isCreated() } }.body()
        assertEquals("Alimentation Mama Nzuzi", paid["merchantName"].asText())
        assertTrue(paid["receiptNumber"].asText().startsWith("CI-"))
        // Le client paie exactement le prix : pas de frais.
        assertEquals(3750, balance(alice))
        // Rejouer la même demande (réseau coupé) ne débite pas deux fois et ne renvoie pas de SMS.
        pay(alice, code, 1250, "m-pay-1").andExpect { status { isOk() }; jsonPath("$.replayed") { value(true) } }
        assertEquals(3750, balance(alice))

        // Commission de 1 % arrondie au centime : 12,50 $ → 0,13 $ ; le marchand reçoit 12,37 $.
        val detail = mvc.get("/v1/backoffice/merchants/${merchant["id"].asText()}") { header("Authorization", "Bearer $admin") }
            .andExpect { status { isOk() } }.body()
        assertEquals(1237, detail["merchant"]["balanceMinor"].asLong())
        val line = detail["payments"].single()
        assertEquals(1250, line["amountMinor"].asLong())
        assertEquals(13, line["commissionMinor"].asLong())
        assertEquals("+243970000701", line["customerPhone"].asText(), "le back-office voit le numéro complet")
        assertEquals(paid["receiptNumber"].asText(), line["receiptNumber"].asText())

        val ref = paid["receiptNumber"].asText()
        assertEquals(
            listOf("Capital Invest: paiement recu 12,50 \$ de Client +243970000701 +243 97* *** 701. Net 12,37 \$. Ref $ref"),
            sms.messages("+243990000101"),
        )
        assertEquals(
            listOf("Capital Invest: paiement de 12,50 \$ a Alimentation Mama Nzuzi. Solde: 37,50 \$. Ref $ref. Pas vous? Appelez-nous."),
            sms.messages(alice.phone),
        )

        // Dans l'historique : un paiement, sans frais, au nom du commerce, filtrable.
        val item = history(alice, "?category=PAYMENT")["items"].single()
        assertEquals("MERCHANT_PAYMENT", item["kind"].asText())
        assertEquals("Alimentation Mama Nzuzi", item["counterpartyName"].asText())
        assertEquals(code, item["merchantCode"].asText())
        assertEquals(1250, item["amountMinor"].asLong())
        assertEquals(0, item["feeMinor"].asLong())
        assertEquals(-1250, item["netMinor"].asLong())
        assertEquals(0, history(alice, "?category=TRANSFER")["items"].size())
    }

    @Test
    fun `marchand suspendu, PIN faux, solde insuffisant et plafond journalier partage avec les envois`() {
        val admin = agent(AgentRole.ADMIN)
        val code = createMerchant(admin).body()["code"].asText()
        val id = mvc.get("/v1/backoffice/merchants") { header("Authorization", "Bearer $admin") }.body()[0]["id"].asText()
        val alice = register("+243970000702")
        val bob = register("+243970000703")
        listOf(20000L, 20000L, 20000L, 1000L).forEachIndexed { n, a ->
            topupConfirmed(alice, a, "m-top-l$n")
            if (n == 0) setKycLevel(alice, 1)
        }

        pay(alice, code, 100, "m-bad-pin", pin = "1111").andExpect { status { isUnauthorized() } }
        pay(alice, code, 1_000_000, "m-too-much").andExpect { status { isUnprocessableEntity() } }

        // 3 × 200 $ chez le marchand : le plafond journalier de 600 $ du niveau 1 est atteint, envois compris.
        repeat(3) { pay(alice, code, 20000, "m-big-$it").andExpect { status { isCreated() } } }
        pay(alice, code, 100, "m-over").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("LIMIT_DAILY") } }
        transfer(alice, bob.phone, 100, "m-tr-over").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("LIMIT_DAILY") } }

        // Marchand suspendu : on ne peut plus le payer.
        mvc.post("/v1/backoffice/merchants/$id/status") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"SUSPENDED","reason":"Plainte client"}"""
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("SUSPENDED") } }
        lookup(bob, code).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("MERCHANT_NOT_ACTIVE") } }
        topupConfirmed(bob, 1000, "m-top-bob")
        pay(bob, code, 100, "m-suspended").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("MERCHANT_NOT_ACTIVE") } }
        assertEquals(1000, balance(bob))
        val actions = jdbc.sql("SELECT action FROM audit_log WHERE target_type = 'MERCHANT' ORDER BY created_at")
            .query(String::class.java).list()
        assertEquals(listOf("MERCHANT_CREATED", "MERCHANT_SUSPENDED"), actions)
    }

    @Test
    fun `seul un administrateur cree un marchand, la conformite consulte`() {
        val compliance = agent(AgentRole.COMPLIANCE)
        createMerchant(compliance).andExpect { status { isForbidden() } }
        val marketing = agent(AgentRole.MARKETING)
        mvc.get("/v1/backoffice/merchants") { header("Authorization", "Bearer $marketing") }.andExpect { status { isForbidden() } }
        mvc.get("/v1/backoffice/merchants") { header("Authorization", "Bearer $compliance") }.andExpect { status { isOk() } }
        val admin = agent(AgentRole.ADMIN)
        createMerchant(admin, phone = "12").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_PHONE") } }
    }
}
