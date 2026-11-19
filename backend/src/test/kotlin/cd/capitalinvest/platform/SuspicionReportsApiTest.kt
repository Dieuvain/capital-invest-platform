package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Déclaration de soupçon : rédigée depuis une alerte, transmise à la CENAREF, puis figée. */
class SuspicionReportsApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    @Test
    fun `brouillon pre-rempli depuis une alerte, redaction, transmission puis dossier fige`() {
        val compliance = agent(AgentRole.COMPLIANCE)
        val mule = register("+243970000950")
        (1..5).forEach { i ->
            val s = register("+24397000095$i")
            topupConfirmed(s, 1_000, "s-top-$i")
            transfer(s, mule.phone, 500, "s-$i").andExpect { status { isCreated() } }
        }
        val alert = mvc.get("/v1/backoffice/monitoring/alerts") { header("Authorization", "Bearer $compliance") }.body()[0]

        mvc.post("/v1/backoffice/suspicion-reports") {
            header("Authorization", "Bearer ${agent(AgentRole.KYC_AGENT)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId":"${mule.customerId}"}"""
        }.andExpect { status { isForbidden() } }

        val draft = mvc.post("/v1/backoffice/suspicion-reports") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId":"${mule.customerId}","alertId":"${alert["id"].asText()}"}"""
        }.andExpect { status { isCreated() } }.body()
        val id = draft["id"].asText()
        assertEquals("DRAFT", draft["status"].asText())
        assertEquals("+243970000950", draft["subject"]["phone"].asText())
        assertTrue(draft["facts"].asText().startsWith("- Argent reçu de nombreux clients"))
        assertEquals(5, draft["operations"].size())
        assertEquals(2_500, draft["totals"]["inMinor"].asLong())
        assertEquals(1, draft["alerts"].size())

        fun submit(ref: String, on: LocalDate) = mvc.post("/v1/backoffice/suspicion-reports/$id/submit") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"submittedOn":"$on","cenarefReference":"$ref"}"""
        }
        val today = LocalDate.now(testClock.withZone(ZoneId.of("Africa/Lubumbashi")))
        submit("DS-2026-0142", today).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("REPORT_INCOMPLETE") } }

        mvc.put("/v1/backoffice/suspicion-reports/$id") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"periodFrom":"${today.minusDays(7)}","periodTo":"$today","facts":"${draft["facts"].asText().replace("\n", "\\n")}",
                "analysis":"Compte collecteur : petites sommes de tiers sans lien apparent, aucune activité commerciale connue."}"""
        }.andExpect { status { isOk() }; jsonPath("$.periodFrom") { value(today.minusDays(7).toString()) } }

        submit("DS-2026-0142", today.plusDays(1)).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_DATE") } }
        val sent = submit("DS-2026-0142", today).andExpect { status { isOk() } }.body()
        assertEquals("SUBMITTED", sent["status"].asText())
        assertEquals("DS-2026-0142", sent["cenarefReference"].asText())
        assertEquals("Agent compliance@capitalinvest.cd", sent["submittedByName"].asText())

        // Transmis : plus de modification.
        mvc.put("/v1/backoffice/suspicion-reports/$id") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"periodFrom":"$today","periodTo":"$today","facts":"x","analysis":"y"}"""
        }.andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("REPORT_SUBMITTED") } }

        val list = mvc.get("/v1/backoffice/suspicion-reports") { header("Authorization", "Bearer $compliance") }.body()
        assertEquals("DS-2026-0142", list[0]["cenarefReference"].asText())
        mvc.get("/v1/backoffice/suspicion-reports/$id") { header("Authorization", "Bearer $compliance") }.andExpect { status { isOk() } }

        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_id = :c::uuid AND action LIKE 'SUSPICION%' ORDER BY created_at")
            .param("c", mule.customerId).query(String::class.java).list()
        assertEquals(listOf("SUSPICION_REPORT_DRAFTED", "SUSPICION_REPORT_SUBMITTED", "SUSPICION_REPORT_VIEWED"), audit)
        // Le client n'en sait rien.
        val titles = jdbc.sql("SELECT title FROM notification WHERE customer_id = :c::uuid").param("c", mule.customerId).query(String::class.java).list()
        assertTrue(titles.none { it.contains("déclar", ignoreCase = true) })
    }
}
