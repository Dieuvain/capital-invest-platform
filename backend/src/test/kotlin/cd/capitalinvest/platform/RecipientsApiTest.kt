package cd.capitalinvest.platform

import cd.capitalinvest.platform.payments.RecipientService
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

class RecipientsApiTest : ApiTest() {
    private fun lookup(s: Session, phone: String) =
        mvc.get("/v1/transfers/recipient") { header("Authorization", "Bearer ${s.token}"); param("phone", phone) }

    @Test
    fun `nom reduit avant l envoi, numero inconnu, soi-meme, compte suspendu`() {
        val alice = register("+243970001601")
        register("+243970001602")
        jdbc.sql("UPDATE customer SET full_name = 'Béni Mukendi Kalala' WHERE phone = '+243970001602'").update()

        lookup(alice, "0970 001 602").andExpect { status { isOk() } }.body().let {
            assertEquals("+243970001602", it["phone"].asText())
            assertEquals("Béni M.", it["displayName"].asText())
        }
        lookup(alice, "0970001699").andExpect { status { isNotFound() } }
        lookup(alice, "+243970001601").andExpect { status { isUnprocessableEntity() } }.body().let { assertEquals("SAME_ACCOUNT", it["code"].asText()) }
        jdbc.sql("UPDATE customer SET status = 'SUSPENDED' WHERE phone = '+243970001602'").update()
        lookup(alice, "0970001602").andExpect { status { isUnprocessableEntity() } }.body().let { assertEquals("RECIPIENT_NOT_ACTIVE", it["code"].asText()) }
        assertEquals("Amani", RecipientService.displayName(" Amani "))
    }

    @Test
    fun `30 numeros par heure, les inconnus comptent aussi`() {
        val eve = register("+243970001603")
        repeat(30) { i -> lookup(eve, "09900016%02d".format(i)).andExpect { status { isNotFound() } } }
        lookup(eve, "0990001699").andExpect { status { isTooManyRequests() } }.body().let { assertEquals("RECIPIENT_LOOKUP_LIMIT", it["code"].asText()) }
    }

    @Test
    fun `destinataires recents, du plus recent au plus ancien, sans doublon`() {
        val alice = register("+243970001604")
        register("+243970001605")
        register("+243970001606")
        jdbc.sql("UPDATE customer SET full_name = 'Josué Kalala' WHERE phone = '+243970001605'").update()
        jdbc.sql("UPDATE customer SET full_name = 'Esther Mbuyi' WHERE phone = '+243970001606'").update()
        topupConfirmed(alice, 10_000, "t-1")
        transfer(alice, "+243970001605", 100, "s-1").andExpect { status { isCreated() } }
        transfer(alice, "+243970001606", 100, "s-2").andExpect { status { isCreated() } }
        transfer(alice, "+243970001605", 100, "s-3").andExpect { status { isCreated() } }
        val recent = mvc.get("/v1/transfers/recent") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(listOf("+243970001605", "+243970001606"), recent.map { it["phone"].asText() })
        assertEquals(listOf("Josué K.", "Esther M."), recent.map { it["displayName"].asText() })
    }
}
