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

/** Surveillance des opérations : les comportements à risque ouvrent une alerte que la conformité traite. */
class MonitoringApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun alerts(token: String, status: String = "OPEN"): JsonNode =
        mvc.get("/v1/backoffice/monitoring/alerts?status=$status") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() } }.body()

    private fun rules(token: String) = alerts(token).map { "${it["rule"].asText()} ${it["customerPhone"].asText()}" }.toSet()

    private fun resolve(token: String, id: String, action: String, note: String) = mvc.post("/v1/backoffice/monitoring/alerts/$id/resolve") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = """{"action":"$action","note":"$note"}"""
    }

    @Test
    fun `montants juste sous le plafond - alerte a la troisieme operation, une seule fois`() {
        val compliance = agent(AgentRole.COMPLIANCE)
        val a = register("+243970000901")
        register("+243970000902")
        repeat(4) { topupConfirmed(a, 20_000, "m-top-$it") }
        // Niveau 1 : plafond de 200,00 $ par opération ; 195,00 $ en est à 97,5 %.
        transfer(a, "+243970000902", 19_500, "m-1").andExpect { status { isCreated() } }
        transfer(a, "+243970000902", 19_500, "m-2").andExpect { status { isCreated() } }
        assertEquals(emptySet(), rules(compliance))
        transfer(a, "+243970000902", 19_500, "m-3").andExpect { status { isCreated() } }
        val open = alerts(compliance)
        assertEquals(1, open.size())
        assertEquals("STRUCTURING", open[0]["rule"].asText())
        assertEquals("+243970000901", open[0]["customerPhone"].asText())
        assertEquals("Montants juste sous le plafond", open[0]["ruleLabel"].asText())
        assertEquals("3 opérations en 24 h entre 180,00 \$ et 200,00 \$ (plafond par opération du niveau 1)", open[0]["detail"].asText())
    }

    @Test
    fun `rafale, compte collecteur et retrait aussitot recu`() {
        val compliance = agent(AgentRole.COMPLIANCE)
        // Compte collecteur : 5 clients différents envoient au même numéro.
        val mule = register("+243970000910")
        (1..5).forEach { i ->
            val s = register("+24397000091$i")
            topupConfirmed(s, 1_000, "f-top-$i")
            transfer(s, mule.phone, 500, "f-$i").andExpect { status { isCreated() } }
        }
        // Rafale : 10 envois en une heure.
        val busy = register("+243970000920")
        topupConfirmed(busy, 5_000, "v-top")
        repeat(10) { transfer(busy, mule.phone, 100, "v-$it").andExpect { status { isCreated() } } }
        // Entrée-sortie : 600,00 $ rechargés, 500,00 $ retirés aussitôt (niveau 2 pour le plafond).
        val cash = register("+243970000930")
        setKycLevel(cash, 2)
        topupConfirmed(cash, 60_000, "c-top")
        mvc.post("/v1/withdrawals") {
            header("Authorization", "Bearer ${cash.token}")
            header("Idempotency-Key", "c-w")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"AIRTEL","amountMinor":50000,"pin":"2580"}"""
        }.andExpect { status { isAccepted() } }

        assertEquals(
            setOf("FAN_IN +243970000910", "VELOCITY +243970000920", "RAPID_CASH_OUT +243970000930"),
            rules(compliance),
        )
        val fanIn = alerts(compliance).first { it["rule"].asText() == "FAN_IN" }
        assertEquals("5 expéditeurs différents en 24 h, pour 25,00 \$", fanIn["detail"].asText())
        // Le client surveillé n'en sait rien : aucune notification ne lui en parle.
        val texts = jdbc.sql("SELECT title FROM notification WHERE customer_id = :c::uuid").param("c", mule.customerId).query(String::class.java).list()
        assertEquals(false, texts.any { it.contains("alerte", ignoreCase = true) || it.contains("suspen", ignoreCase = true) })
    }

    @Test
    fun `la conformite classe sans suite ou suspend le compte, puis le reactive`() {
        val compliance = agent(AgentRole.COMPLIANCE)
        val mule = register("+243970000940")
        (1..5).forEach { i ->
            val s = register("+24397000094$i")
            topupConfirmed(s, 1_000, "r-top-$i")
            transfer(s, mule.phone, 500, "r-$i").andExpect { status { isCreated() } }
        }
        val alert = alerts(compliance)[0]["id"].asText()

        mvc.get("/v1/backoffice/monitoring/alerts") { header("Authorization", "Bearer ${agent(AgentRole.KYC_AGENT)}") }
            .andExpect { status { isForbidden() } }
        resolve(compliance, alert, "SUSPEND", "").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("NOTE_REQUIRED") } }

        val done = resolve(compliance, alert, "SUSPEND", "Collecte pour le compte de tiers, déclaration à la CENAREF")
            .andExpect { status { isOk() } }.body()
        assertEquals("ESCALATED", done["status"].asText())
        assertEquals("SUSPENDED", done["customerStatus"].asText())
        assertEquals("Agent compliance@capitalinvest.cd", done["resolvedByName"].asText())
        resolve(compliance, alert, "DISMISS", "Deuxième clic").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("ALERT_ALREADY_RESOLVED") } }
        assertEquals(0, alerts(compliance).size())
        assertEquals(1, alerts(compliance, "CLOSED").size())

        // Suspendu : la session ouverte tombe et la connexion est refusée.
        mvc.get("/v1/me/balance") { header("Authorization", "Bearer ${mule.token}") }.andExpect { status { isUnauthorized() } }
        loginRequest(mule.phone, "2580").andExpect { status { isForbidden() }; jsonPath("$.code") { value("CUSTOMER_NOT_ACTIVE") } }

        mvc.post("/v1/backoffice/customers/${mule.customerId}/status") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"ACTIVE","reason":"Justificatifs reçus, activité de commerce"}"""
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("ACTIVE") } }
        loginRequest(mule.phone, "2580").andExpect { status { isOk() } }

        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_id = :c::uuid ORDER BY created_at").param("c", mule.customerId)
            .query(String::class.java).list()
        assertEquals(
            listOf("MONITORING_ALERT_OPENED", "MONITORING_ALERT_ESCALATED", "CUSTOMER_SUSPENDED", "CUSTOMER_REACTIVATED"),
            audit,
        )
    }
}
