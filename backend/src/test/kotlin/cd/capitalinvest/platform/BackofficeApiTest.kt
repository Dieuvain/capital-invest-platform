package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post

/** Ce dont l'écran back-office a besoin en plus de la KYC et des promotions. */
class BackofficeApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private val password = "mot-de-passe-solide"

    private fun agent(email: String, role: AgentRole): Pair<String, String> {
        val id = agents.create(null, email, "Agent $email", password, role).id.toString()
        val token = mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.body()["accessToken"].asText()
        return id to token
    }

    @Test
    fun `l'agent retrouve son profil et son rôle`() {
        val (id, token) = agent("kyc@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/me") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            jsonPath("$.role") { value("KYC_AGENT") }
        }
        mvc.get("/v1/backoffice/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `l'administrateur liste et désactive un agent, ce qui ferme ses sessions`() {
        val (_, admin) = agent("admin@capitalinvest.cd", AgentRole.ADMIN)
        val (kycId, kyc) = agent("kyc@capitalinvest.cd", AgentRole.KYC_AGENT)

        mvc.get("/v1/backoffice/agents") { header("Authorization", "Bearer $admin") }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
        mvc.get("/v1/backoffice/agents") { header("Authorization", "Bearer $kyc") }.andExpect { status { isForbidden() } }

        mvc.post("/v1/backoffice/agents/$kycId/disable") { header("Authorization", "Bearer $admin") }.andExpect {
            status { isOk() }
            jsonPath("$.active") { value(false) }
        }
        mvc.get("/v1/backoffice/me") { header("Authorization", "Bearer $kyc") }.andExpect { status { isUnauthorized() } }
        mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"kyc@capitalinvest.cd","password":"$password"}"""
        }.andExpect { status { isForbidden() } }

        mvc.post("/v1/backoffice/agents/$kycId/enable") { header("Authorization", "Bearer $admin") }.andExpect {
            jsonPath("$.active") { value(true) }
        }
    }

    @Test
    fun `un administrateur ne peut pas se désactiver lui-même`() {
        val (id, admin) = agent("admin@capitalinvest.cd", AgentRole.ADMIN)
        mvc.post("/v1/backoffice/agents/$id/disable") { header("Authorization", "Bearer $admin") }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("AGENT_SELF_DISABLE") }
        }
    }

    @Test
    fun `le journal d'audit est lisible par la conformité, pas par un agent KYC`() {
        val (_, compliance) = agent("conformite@capitalinvest.cd", AgentRole.COMPLIANCE)
        val (_, kyc) = agent("kyc@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/audit") { header("Authorization", "Bearer $compliance") }.andExpect {
            status { isOk() }
            jsonPath("$[0].action") { value("AGENT_LOGIN") }
            jsonPath("$[0].actorName") { value("Agent kyc@capitalinvest.cd") }
        }
        mvc.get("/v1/backoffice/audit") { header("Authorization", "Bearer $kyc") }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("COMPLIANCE_ONLY") }
        }
    }

    @Test
    fun `l'écran back-office sur son propre domaine passe la vérification CORS`() {
        mvc.options("/v1/backoffice/kyc") {
            header("Origin", "https://backoffice.capitalinvest.test")
            header("Access-Control-Request-Method", "GET")
            header("Access-Control-Request-Headers", "Authorization")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "https://backoffice.capitalinvest.test") }
        }
        mvc.options("/v1/backoffice/kyc") {
            header("Origin", "https://pirate.example")
            header("Access-Control-Request-Method", "GET")
        }.andExpect { status { isForbidden() } }
    }
}
