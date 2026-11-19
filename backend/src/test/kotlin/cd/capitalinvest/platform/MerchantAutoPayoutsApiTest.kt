package cd.capitalinvest.platform

import cd.capitalinvest.platform.auth.SimulatedSmsGateway
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import cd.capitalinvest.platform.merchants.MerchantService
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import kotlin.test.assertEquals

/** Reversement automatique : chaque nuit, ce qui est dû au marchand part tout seul vers son compte. */
class MerchantAutoPayoutsApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var merchants: MerchantService
    @Autowired lateinit var sms: SimulatedSmsGateway

    private val night = LocalDate.of(2026, 11, 19)

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

    /** Un marchand avec un compte M-Pesa de reversement, qui a reçu 50,00 $ (49,50 $ après commission). */
    private fun merchantWithSales(admin: String): JsonNode {
        val m = mvc.post("/v1/backoffice/merchants") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Boulangerie Kin","phone":"0990000202","payoutOperator":"MPESA","payoutPhone":"0810000202"}"""
        }.andExpect { status { isCreated() } }.body()
        val client = register("+243970000802")
        topupConfirmed(client, 5000, "auto-top")
        mvc.post("/v1/merchant-payments") {
            header("Authorization", "Bearer ${client.token}")
            header("Idempotency-Key", "auto-pay")
            contentType = MediaType.APPLICATION_JSON
            content = """{"merchantCode":"${m["code"].asText()}","amountMinor":5000,"pin":"2580"}"""
        }.andExpect { status { isCreated() } }
        sms.clear()
        return m
    }

    private fun detail(admin: String, id: String) =
        mvc.get("/v1/backoffice/merchants/$id") { header("Authorization", "Bearer $admin") }.andExpect { status { isOk() } }.body()

    private fun setAuto(token: String, id: String, enabled: Boolean) = mvc.post("/v1/backoffice/merchants/$id/auto-payout") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = """{"enabled":$enabled}"""
    }

    @Test
    fun `la nuit, tout ce qui est du part une seule fois et le marchand recoit un SMS`() {
        val admin = agent(AgentRole.ADMIN)
        val m = merchantWithSales(admin)
        val id = m["id"].asText()
        assertEquals(true, m["autoPayout"].asBoolean())

        assertEquals(1, merchants.runAutoPayouts(night).sent)
        val p = detail(admin, id)["payouts"][0]
        assertEquals(4950, p["amountMinor"].asLong())
        assertEquals(true, p["automatic"].asBoolean())
        assertEquals("+243810000202", p["phone"].asText())
        assertEquals(0, detail(admin, id)["merchant"]["balanceMinor"].asLong())

        // La tâche qui repasse la même nuit ne reverse pas une seconde fois.
        val again = merchants.runAutoPayouts(night)
        assertEquals(0, again.sent)
        mvc.post("/v1/simulators/mobile-money/payouts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"withdrawalId":"${p["id"].asText()}","success":true}"""
        }.andExpect { status { isOk() } }
        assertEquals(0, merchants.runAutoPayouts(night).sent)
        assertEquals(1, detail(admin, id)["payouts"].size())
        assertEquals(listOf("Capital Invest: reversement de 49,50 \$ verse sur votre M-Pesa +243810000202."), sms.messages("+243990000202"))

        val audit = jdbc.sql("SELECT actor_type || ' ' || action FROM audit_log WHERE target_type = 'MERCHANT' ORDER BY created_at")
            .query(String::class.java).list()
        assertEquals(listOf("AGENT MERCHANT_CREATED", "SYSTEM MERCHANT_PAYOUT_AUTOMATIC"), audit)
    }

    @Test
    fun `rien ne part si le reversement automatique est coupe, le compte vient de changer ou le solde est trop petit`() {
        val admin = agent(AgentRole.ADMIN)
        val id = merchantWithSales(admin)["id"].asText()

        // Seul un administrateur coupe ou remet le reversement automatique.
        setAuto(agent(AgentRole.COMPLIANCE), id, false).andExpect { status { isForbidden() } }
        setAuto(admin, id, false).andExpect { status { isOk() }; jsonPath("$.autoPayout") { value(false) } }
        assertEquals(0, merchants.runAutoPayouts(night).sent)
        setAuto(admin, id, true).andExpect { status { isOk() }; jsonPath("$.autoPayout") { value(true) } }

        // Compte changé il y a moins de 24 h : on attend.
        mvc.post("/v1/backoffice/merchants/$id/payout-account") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"AIRTEL","phone":"0970000202"}"""
        }.andExpect { status { isOk() } }
        val r = merchants.runAutoPayouts(night)
        assertEquals(0, r.sent)
        assertEquals(1, r.skipped)

        // Un reversement manuel laisse 9,50 $ : sous le minimum de nuit (10,00 $), rien ne part.
        testClock.advance(java.time.Duration.ofHours(25))
        val admin2 = agent(AgentRole.ADMIN)
        mvc.post("/v1/backoffice/merchants/$id/payouts") {
            header("Authorization", "Bearer $admin2")
            contentType = MediaType.APPLICATION_JSON
            content = """{"requestId":"manuel","amountMinor":4000}"""
        }.andExpect { status { isAccepted() }; jsonPath("$.automatic") { value(false) } }
        val pending = merchants.runAutoPayouts(night.plusDays(1))
        assertEquals(0, pending.sent) // reversement manuel encore en cours
        val manual = detail(admin2, id)["payouts"][0]["id"].asText()
        mvc.post("/v1/simulators/mobile-money/payouts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"withdrawalId":"$manual","success":true}"""
        }.andExpect { status { isOk() } }
        val small = merchants.runAutoPayouts(night.plusDays(1))
        assertEquals(0, small.sent)
        assertEquals(1, small.skipped)
        assertEquals(950, detail(admin2, id)["merchant"]["balanceMinor"].asLong())
    }
}
