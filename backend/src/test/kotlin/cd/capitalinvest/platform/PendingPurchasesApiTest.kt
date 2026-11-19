package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import cd.capitalinvest.platform.payments.airtime.AirtimeService
import cd.capitalinvest.platform.payments.bills.BillService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingPurchasesApiTest : ApiTest() {
    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var airtime: AirtimeService
    @Autowired lateinit var bills: BillService

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun buyAirtime(s: Session, phone: String, amountMinor: Long, key: String) = mvc.post("/v1/airtime") {
        header("Authorization", "Bearer ${s.token}")
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","amountMinor":$amountMinor,"pin":"2580"}"""
    }.andExpect { status { isCreated() } }.body()

    private fun payBill(s: Session, biller: String, reference: String, amountMinor: Long, key: String) = mvc.post("/v1/bills") {
        header("Authorization", "Bearer ${s.token}")
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"biller":"$biller","reference":"$reference","amountMinor":$amountMinor,"pin":"2580"}"""
    }.andExpect { status { isCreated() } }.body()

    private fun resolve(token: String, path: String, body: String) = mvc.post("/v1/backoffice/$path/resolve") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @Test
    fun `sans reponse a l'envoi, la relance confirme le credit`() {
        val alice = register("+243970001301")
        topupConfirmed(alice, 5_000, "t-1")
        val p = buyAirtime(alice, "0815555555", 500, "a-1")
        assertEquals("PENDING", p["status"].asText())
        assertEquals(4_500, balance(alice))

        airtime.checkDue() // trop tôt : pas encore l'heure de la relance
        assertEquals("PENDING", jdbc.sql("SELECT status FROM airtime_purchase").query(String::class.java).single())
        testClock.advance(Duration.ofMinutes(2))
        airtime.checkDue()
        assertEquals("DELIVERED", jdbc.sql("SELECT status FROM airtime_purchase").query(String::class.java).single())
        assertEquals(4_500, balance(login(alice.phone)))
    }

    @Test
    fun `24 h sans reponse, un agent tranche, code du compteur exige, puis remboursement d un credit`() {
        val bob = register("+243970001302")
        topupConfirmed(bob, 10_000, "t-1")
        val bill = payBill(bob, "SNEL_PREPAID", "01234566666", 2_000, "b-1")
        val credit = buyAirtime(bob, "0976666666", 1_000, "a-1")
        assertEquals("PENDING", bill["status"].asText())
        assertEquals(7_000, balance(bob))

        // Relances espacées, puis à 24 h : à vérifier par un agent. Jamais de remboursement automatique sur un doute.
        repeat(3) {
            testClock.advance(Duration.ofHours(8) + Duration.ofMinutes(5))
            bills.checkDue()
            airtime.checkDue()
        }
        assertEquals(listOf("REVIEW"), jdbc.sql("SELECT DISTINCT status FROM bill_payment").query(String::class.java).list())
        assertEquals("REVIEW", jdbc.sql("SELECT status FROM airtime_purchase").query(String::class.java).single())
        val me = login(bob.phone)
        assertEquals(7_000, balance(me))
        val item = mvc.get("/v1/me/history/${bill["transactionId"].asText()}") { header("Authorization", "Bearer ${me.token}") }.body()["item"]
        assertEquals("REVIEW", item["status"].asText())

        mvc.get("/v1/backoffice/purchases/review") { header("Authorization", "Bearer ${agent(AgentRole.KYC_AGENT)}") }
            .andExpect { status { isForbidden() } }
        val compliance = agent(AgentRole.COMPLIANCE)
        val queue = mvc.get("/v1/backoffice/purchases/review") { header("Authorization", "Bearer $compliance") }
            .andExpect { status { isOk() } }.body()
        assertEquals(listOf("AIRTIME", "BILL").sorted(), queue.map { it["type"].asText() }.sorted())
        val snel = queue.first { it["type"].asText() == "BILL" }
        assertEquals(true, snel["needsToken"].asBoolean())
        assertEquals("01234566666", snel["target"].asText())

        resolve(compliance, "bills/${bill["id"].asText()}", """{"action":"DONE","note":"Confirmé par l'agrégateur"}""")
            .andExpect { jsonPath("$.code") { value("TOKEN_REQUIRED") } }
        resolve(compliance, "bills/${bill["id"].asText()}", """{"action":"DONE","note":"Confirmé par l'agrégateur","token":"1111 2222 3333 4444 5555"}""")
            .andExpect { status { isOk() }; jsonPath("$.status") { value("PAID") }; jsonPath("$.token") { value("1111 2222 3333 4444 5555") } }
        val sms = jdbc.sql("SELECT sms_text FROM notification WHERE kind = 'BILL_PAID'").query(String::class.java).single()
        assertTrue(sms.endsWith("Code: 1111 2222 3333 4444 5555"), sms)

        resolve(compliance, "airtime/${credit["id"].asText()}", """{"action":"REFUND","note":"Échec confirmé par le prestataire"}""")
            .andExpect { status { isOk() }; jsonPath("$.status") { value("FAILED") } }
        assertEquals(8_000, balance(me))
        resolve(compliance, "airtime/${credit["id"].asText()}", """{"action":"REFUND","note":"encore"}""")
            .andExpect { jsonPath("$.code") { value("ALREADY_RESOLVED") } }
        val kinds = jdbc.sql("SELECT kind FROM notification WHERE customer_id = :c::uuid ORDER BY created_at")
            .param("c", bob.customerId).query(String::class.java).list()
        assertTrue("AIRTIME_REFUNDED" in kinds, kinds.toString())
        val audit = jdbc.sql("SELECT action FROM audit_log WHERE action LIKE '%_REVIEW_%' ORDER BY created_at").query(String::class.java).list()
        assertEquals(listOf("BILL_REVIEW_DONE", "AIRTIME_REVIEW_REFUND"), audit)
        val queueAfter = mvc.get("/v1/backoffice/purchases/review") { header("Authorization", "Bearer $compliance") }.body()
        assertEquals(0, queueAfter.size())
    }
}
