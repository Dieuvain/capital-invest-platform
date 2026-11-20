package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

/** Page « Activité » du back-office : chiffres par jour recalculés à partir du grand livre. */
class ActivityApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun activity(token: String, days: Int = 7): JsonNode =
        mvc.get("/v1/backoffice/activity?days=$days") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.body()

    @Test
    fun `rechargements, envois et retraits du jour, frais compris`() {
        val marketing = agent(AgentRole.MARKETING)
        val before = activity(marketing)
        val a = register("+243970001501")
        val b = register("+243970001502")
        topupConfirmed(a, 10_000, "act-top-1")
        topupConfirmed(b, 2_000, "act-top-2")
        transfer(a, b.phone, 3_000, "act-tr-1").andExpect { status { isCreated() } }
        mvc.post("/v1/withdrawals") {
            header("Authorization", "Bearer ${a.token}")
            header("Idempotency-Key", "act-w-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"AIRTEL","amountMinor":2000,"pin":"2580"}"""
        }.andExpect { status { isAccepted() } }

        val now = activity(marketing)
        assertEquals(7, now["days"].size())
        val today = now["days"][6]
        assertEquals(now["to"].asText(), today["date"].asText())
        fun delta(path: String, field: String) =
            now["totals"][path][field].asLong() - before["totals"][path][field].asLong()
        assertEquals(2, today["newCustomers"].asLong() - before["days"][6]["newCustomers"].asLong())
        assertEquals(2, delta("topups", "count"))
        assertEquals(12_000, delta("topups", "amountMinor"))
        assertEquals(1, delta("transfers", "count"))
        assertEquals(3_000, delta("transfers", "amountMinor"))
        assertEquals(1, delta("withdrawals", "count"))
        assertEquals(2_000, delta("withdrawals", "amountMinor"))
        // Frais : ce que paient les clients en plus des montants, ni plus ni moins.
        val spent = 10_000L + 2_000 - balance(a) - balance(b)
        val withdrawn = 2_000L
        assertEquals(spent - withdrawn, now["totals"]["feesMinor"].asLong() - before["totals"]["feesMinor"].asLong())
        assertEquals(true, now["customerHoldingsMinor"].asLong() >= balance(a) + balance(b))
        assertEquals(true, now["totals"]["activeCustomers"].asLong() >= 2)
        assertEquals(7, java.time.LocalDate.parse(now["from"].asText()).until(java.time.LocalDate.parse(now["to"].asText())).days + 1)
    }

    @Test
    fun `reserve a la direction, periode bornee`() {
        mvc.get("/v1/backoffice/activity") { header("Authorization", "Bearer ${agent(AgentRole.KYC_AGENT)}") }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("ACTIVITY_FORBIDDEN") } }
        val admin = agent(AgentRole.ADMIN)
        assertEquals(30, activity(admin, 30)["days"].size())
        listOf(0, 91).forEach { d ->
            mvc.get("/v1/backoffice/activity?days=$d") { header("Authorization", "Bearer $admin") }
                .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_PERIOD") } }
        }
    }
}
