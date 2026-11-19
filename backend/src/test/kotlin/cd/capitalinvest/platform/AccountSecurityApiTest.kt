package cd.capitalinvest.platform

import cd.capitalinvest.platform.auth.SimulatedSmsGateway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

class AccountSecurityApiTest : ApiTest() {
    @Autowired lateinit var sms: SimulatedSmsGateway

    private fun changePin(s: Session, current: String, new: String) = mvc.post("/v1/me/security/pin") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"currentPin":"$current","newPin":"$new"}"""
    }

    private fun loginOn(phone: String, deviceId: String, deviceName: String) = mvc.post("/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","pin":"2580","deviceId":"$deviceId","deviceName":"$deviceName"}"""
    }.andExpect { status { isOk() } }.body().let { Session(it["accessToken"].asText(), "", "", phone) }

    private fun inbox(s: Session) =
        mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${s.token}") }.body()["items"].map { it["kind"].asText() }

    @Test
    fun `changer son PIN ferme les autres telephones mais garde celui-ci, sans delai sur les envois`() {
        val alice = register("+243970000801")
        val bob = register("+243970000802")
        topupConfirmed(alice, 5_000, "t-1")
        val other = loginOn(alice.phone, "tel-ancien", "Tecno Spark 10")

        changePin(alice, "1111", "7391").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("INVALID_CREDENTIALS") } }
        changePin(alice, "2580", "2580").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PIN_SAME") } }
        changePin(alice, "2580", "1234").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PIN_TOO_SIMPLE") } }
        changePin(alice, "2580", "7391").andExpect { status { isNoContent() } }

        mvc.get("/v1/me") { header("Authorization", "Bearer ${other.token}") }.andExpect { status { isUnauthorized() } }
        transfer(alice, bob.phone, 1_000, "e-1", pin = "7391").andExpect { status { isCreated() } }
        loginRequest(alice.phone, "2580").andExpect { status { isUnauthorized() } }
        assert("PIN_CHANGED" in inbox(alice))
    }

    @Test
    fun `connexions recentes, alerte nouveau telephone et deconnexion des autres`() {
        val alice = register("+243970000803")
        sms.clear()
        val phone2 = loginOn(alice.phone, "tel-2", "Samsung Galaxy A14")
        assertEquals("NEW_DEVICE", inbox(alice).first())
        assert(sms.messages(alice.phone).single().contains("nouveau telephone (Samsung Galaxy A14)"))

        // Même téléphone une deuxième fois : pas de nouvelle alerte.
        loginOn(alice.phone, "tel-2", "Samsung Galaxy A14")
        assertEquals(1, sms.messages(alice.phone).size)

        val list = mvc.get("/v1/me/security/sessions") { header("Authorization", "Bearer ${phone2.token}") }
            .andExpect { status { isOk() } }.body()
        assertEquals(3, list.size())
        assertEquals(true, list[0]["current"].asBoolean())
        assertEquals("Samsung Galaxy A14", list[0]["deviceName"].asText())
        assertEquals(listOf(false, false), list.drop(1).map { it["current"].asBoolean() })

        mvc.post("/v1/me/security/sessions/sign-out-others") { header("Authorization", "Bearer ${phone2.token}") }
            .andExpect { status { isOk() }; jsonPath("$.closed") { value(2) } }
        mvc.get("/v1/me") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isUnauthorized() } }
        mvc.get("/v1/me/security/sessions") { header("Authorization", "Bearer ${phone2.token}") }.body().let { after ->
            assertEquals(listOf(true, false, false), after.map { it["active"].asBoolean() })
        }
    }
}
