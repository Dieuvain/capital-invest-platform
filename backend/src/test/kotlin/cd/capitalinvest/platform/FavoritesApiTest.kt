package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

class FavoritesApiTest : ApiTest() {

    private fun save(s: Session, phone: String, nickname: String) = mvc.post("/v1/me/favorites") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","nickname":"$nickname"}"""
    }

    private fun list(s: Session) = mvc.get("/v1/me/favorites") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()

    @Test
    fun `favoris avec surnom, nom verifie, mise a jour, suppression, comptes suspendus caches`() {
        val alice = register("+243970002101")
        val mama = register("+243970002102")
        register("+243970002103")

        save(alice, "0970002102", "  Maman   Chantal ").andExpect { status { isOk() } }.body().let {
            assertEquals("Maman Chantal", it["nickname"].asText())
            assertEquals("+243970002102", it["phone"].asText())
            assertEquals("Client +", it["displayName"].asText().take(8))
        }
        save(alice, "0970002103", "Loyer").andExpect { status { isOk() } }
        // Le même destinataire : le surnom change, pas de doublon.
        save(alice, "+243970002102", "Maman").andExpect { status { isOk() } }
        assertEquals(listOf("Loyer", "Maman"), list(alice).map { it["nickname"].asText() })

        save(alice, "0990000000", "Inconnu").andExpect { status { isNotFound() } }
        save(alice, "0970002101", "Moi").andExpect { jsonPath("$.code") { value("SAME_ACCOUNT") } }
        save(alice, "0970002103", "").andExpect { jsonPath("$.code") { value("INVALID_NICKNAME") } }

        // Suspendu : il disparaît de la liste sans être effacé.
        jdbc.sql("UPDATE customer SET status = 'SUSPENDED' WHERE id = :id::uuid").param("id", mama.customerId).update()
        assertEquals(listOf("Loyer"), list(alice).map { it["nickname"].asText() })

        val id = list(alice)[0]["id"].asText()
        mvc.delete("/v1/me/favorites/$id") { header("Authorization", "Bearer ${mama.let { login(alice.phone) }.token}") }
            .andExpect { status { isNoContent() } }
        assertEquals(0, list(login(alice.phone)).size())
        // Le favori d'un autre client : introuvable.
        mvc.delete("/v1/me/favorites/$id") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isNotFound() } }
    }
}
