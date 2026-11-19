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
import java.time.Duration
import kotlin.test.assertEquals

/** Reversement aux marchands : ce qui leur est dû part vers leur compte mobile money. */
class MerchantPayoutsApiTest : ApiTest() {

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

    /** Un marchand qui a reçu 50,00 $ (49,50 $ après 1 % de commission). */
    private fun merchantWithSales(admin: String, payoutAccount: String?): JsonNode {
        val m = mvc.post("/v1/backoffice/merchants") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Pharmacie du Centre","phone":"0990000101"${payoutAccount ?: ""}}"""
        }.andExpect { status { isCreated() } }.body()
        val client = register("+243970000801")
        topupConfirmed(client, 5000, "po-top")
        mvc.post("/v1/merchant-payments") {
            header("Authorization", "Bearer ${client.token}")
            header("Idempotency-Key", "po-pay")
            contentType = MediaType.APPLICATION_JSON
            content = """{"merchantCode":"${m["code"].asText()}","amountMinor":5000,"pin":"2580"}"""
        }.andExpect { status { isCreated() } }
        sms.clear()
        return m
    }

    private fun detail(admin: String, id: String) =
        mvc.get("/v1/backoffice/merchants/$id") { header("Authorization", "Bearer $admin") }.andExpect { status { isOk() } }.body()

    private fun payout(token: String, id: String, requestId: String, amount: Long? = null) =
        mvc.post("/v1/backoffice/merchants/$id/payouts") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"requestId":"$requestId"${amount?.let { ",\"amountMinor\":$it" } ?: ""}}"""
        }

    private fun simulate(withdrawalId: String, success: Boolean) = mvc.post("/v1/simulators/mobile-money/payouts") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"withdrawalId":"$withdrawalId","success":$success}"""
    }.andExpect { status { isOk() } }

    @Test
    fun `tout ce qui est du part vers le M-Pesa du marchand, une seule fois, et il recoit un SMS`() {
        val admin = agent(AgentRole.ADMIN)
        val m = merchantWithSales(admin, ""","payoutOperator":"MPESA","payoutPhone":"0810000101"""")
        val id = m["id"].asText()
        assertEquals("+243810000101", m["payoutPhone"].asText())
        assertEquals(4950, detail(admin, id)["merchant"]["balanceMinor"].asLong())

        val p = payout(admin, id, "req-1").andExpect { status { isAccepted() } }.body()
        assertEquals("PENDING", p["status"].asText())
        assertEquals(4950, p["amountMinor"].asLong())
        // Un double clic renvoie le même reversement.
        assertEquals(p["id"], payout(admin, id, "req-1").andExpect { status { isAccepted() } }.body()["id"])
        // L'argent est bloqué dès la demande : rien d'autre ne peut partir.
        assertEquals(0, detail(admin, id)["merchant"]["balanceMinor"].asLong())
        payout(admin, id, "req-2", 100).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INSUFFICIENT_FUNDS") } }

        simulate(p["id"].asText(), success = true)
        val payouts = detail(admin, id)["payouts"]
        assertEquals(1, payouts.size())
        assertEquals("CONFIRMED", payouts[0]["status"].asText())
        assertEquals(listOf("Capital Invest: reversement de 49,50 \$ verse sur votre M-Pesa +243810000101."), sms.messages("+243990000101"))

        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_type = 'MERCHANT' ORDER BY created_at").query(String::class.java).list()
        assertEquals(listOf("MERCHANT_CREATED", "MERCHANT_PAYOUT_REQUESTED"), audit)
    }

    @Test
    fun `versement refuse par l operateur - l argent revient sur le compte du marchand`() {
        val admin = agent(AgentRole.ADMIN)
        val id = merchantWithSales(admin, ""","payoutOperator":"AIRTEL","payoutPhone":"+243 970 000 999"""")["id"].asText()
        val p = payout(admin, id, "req-f", 2000).andExpect { status { isAccepted() } }.body()
        assertEquals(2950, detail(admin, id)["merchant"]["balanceMinor"].asLong())
        simulate(p["id"].asText(), success = false)
        val d = detail(admin, id)
        assertEquals(4950, d["merchant"]["balanceMinor"].asLong())
        assertEquals("FAILED", d["payouts"][0]["status"].asText())
        assertEquals(
            listOf("Capital Invest: Airtel Money n'a pas pu verser 20,00 \$ sur +243970000999. L'argent reste sur votre compte. Appelez-nous."),
            sms.messages("+243990000101"),
        )
    }

    @Test
    fun `compte de reversement - absent, premier compte, puis changement qui bloque 24 h et previent le marchand`() {
        val admin = agent(AgentRole.ADMIN)
        val id = merchantWithSales(admin, null)["id"].asText()
        payout(admin, id, "r1").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PAYOUT_ACCOUNT_MISSING") } }

        fun setAccount(op: String, phone: String) = mvc.post("/v1/backoffice/merchants/$id/payout-account") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"$op","phone":"$phone"}"""
        }.andExpect { status { isOk() } }.body()

        // Premier compte : utilisable tout de suite.
        assertEquals(true, setAccount("ORANGE", "0850000101")["payoutBlockedUntil"].isNull)
        payout(admin, id, "r2", 1000).andExpect { status { isAccepted() } }

        // Changement : le marchand est prévenu sur son numéro habituel, et rien ne part pendant 24 h.
        sms.clear()
        val changed = setAccount("MPESA", "0820000666")
        assertEquals(false, changed["payoutBlockedUntil"].isNull)
        assertEquals(
            listOf("Capital Invest: vos reversements iront desormais sur M-Pesa +243820000666. Pas vous? Appelez-nous vite."),
            sms.messages("+243990000101"),
        )
        payout(admin, id, "r3", 1000).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PAYOUT_ACCOUNT_RECENTLY_CHANGED") } }
        testClock.advance(Duration.ofHours(25))
        val admin2 = agent(AgentRole.ADMIN) // la session de la veille a expiré
        payout(admin2, id, "r4", 1000).andExpect { status { isAccepted() }; jsonPath("$.phone") { value("+243820000666") } }
        payout(admin2, id, "r5", 50).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PAYOUT_TOO_SMALL") } }

        // La conformité voit les reversements mais ne les déclenche pas.
        val compliance = agent(AgentRole.COMPLIANCE)
        payout(compliance, id, "r6", 1000).andExpect { status { isForbidden() } }
        assertEquals(2, detail(compliance, id)["payouts"].size())
    }
}
