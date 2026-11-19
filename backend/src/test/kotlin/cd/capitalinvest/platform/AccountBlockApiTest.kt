package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountBlockApiTest : ApiTest() {
    @Autowired lateinit var agents: AgentService

    private fun blockFromPhone(phone: String, pin: String) = mvc.post("/v1/auth/block") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","pin":"$pin"}"""
    }

    private fun compliance(): String {
        runCatching { agents.create(null, "conformite@capitalinvest.cd", "Agent conformité", "mot-de-passe-solide", AgentRole.COMPLIANCE) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"conformite@capitalinvest.cd","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun sms(phone: String) = mvc.get("/v1/simulators/sms/$phone").body()["text"].asText()

    @Test
    fun `telephone vole, le client bloque son compte depuis un autre telephone puis l agence le debloque`() {
        val amani = register("+243970001601")
        register("+243970001602")
        topupConfirmed(amani, 5_000, "t-1")

        // Sans le bon PIN, rien ne se passe : un inconnu ne peut pas bloquer le compte d'un autre.
        blockFromPhone("+243970001601", "1111").andExpect { status { isUnauthorized() } }
        blockFromPhone("+243990000999", "2580").andExpect { status { isUnauthorized() } }
        assertEquals(5_000, balance(amani))

        blockFromPhone("+243970001601", "2580").andExpect { status { isNoContent() } }
        blockFromPhone("+243970001601", "2580").andExpect { status { isNoContent() } } // déjà bloqué : rien ne change
        // La session du téléphone volé tombe, la connexion est refusée avec la marche à suivre.
        mvc.get("/v1/me/balance") { header("Authorization", "Bearer ${amani.token}") }.andExpect { status { isUnauthorized() } }
        loginRequest(amani.phone, "2580").andExpect { status { isForbidden() }; jsonPath("$.code") { value("CUSTOMER_BLOCKED") } }
        assertTrue(sms(amani.phone).contains("bloque a votre demande"))
        // Personne ne peut plus lui envoyer d'argent non plus.
        val bob = login("+243970001602")
        transfer(bob, "+243970001601", 100, "s-1").andExpect { status { isUnprocessableEntity() } }

        val token = compliance()
        mvc.get("/v1/backoffice/customers/${amani.customerId}") { header("Authorization", "Bearer $token") }.body().let {
            assertEquals("SUSPENDED", it["status"].asText())
            assertTrue(!it["blockedByCustomerAt"].isNull)
        }
        mvc.post("/v1/backoffice/customers/${amani.customerId}/status") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"ACTIVE","reason":"Pièce d'identité vérifiée en agence"}"""
        }.andExpect { status { isOk() } }
        assertTrue(sms(amani.phone).contains("debloque"))
        mvc.get("/v1/backoffice/customers/${amani.customerId}") { header("Authorization", "Bearer $token") }.body().let {
            assertTrue(it["blockedByCustomerAt"].isNull)
        }
        assertEquals(5_000, balance(login(amani.phone)))

        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_id = :c::uuid ORDER BY created_at").param("c", amani.customerId)
            .query(String::class.java).list()
        assertTrue(audit.containsAll(listOf("CUSTOMER_SELF_BLOCKED", "CUSTOMER_REACTIVATED")))
    }

    @Test
    fun `bloquer depuis l app demande le PIN, et ne touche pas a une suspension de la conformite`() {
        val carl = register("+243970001603")
        mvc.post("/v1/me/security/block") {
            header("Authorization", "Bearer ${carl.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"pin":"0000"}"""
        }.andExpect { status { isUnauthorized() } }
        mvc.post("/v1/me/security/block") {
            header("Authorization", "Bearer ${carl.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"pin":"2580"}"""
        }.andExpect { status { isNoContent() } }
        mvc.get("/v1/me/balance") { header("Authorization", "Bearer ${carl.token}") }.andExpect { status { isUnauthorized() } }

        // Suspendu par la conformité : le client ne peut pas transformer ça en « bloqué à sa demande ».
        val dina = register("+243970001604")
        val token = compliance()
        mvc.post("/v1/backoffice/customers/${dina.customerId}/status") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"SUSPENDED","reason":"Activité inhabituelle à vérifier"}"""
        }.andExpect { status { isOk() } }
        blockFromPhone(dina.phone, "2580").andExpect { status { isNoContent() } }
        loginRequest(dina.phone, "2580").andExpect { status { isForbidden() }; jsonPath("$.code") { value("CUSTOMER_NOT_ACTIVE") } }
    }
}
