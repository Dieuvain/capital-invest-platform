package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertNull

class PinResetApiTest : ApiTest() {

    private fun requestCode(phone: String) = mvc.post("/v1/auth/pin-reset/otp") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone"}"""
    }

    private fun reset(phone: String, code: String, newPin: String) = mvc.post("/v1/auth/pin-reset") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","code":"$code","newPin":"$newPin","deviceId":"tel-neuf"}"""
    }

    @Test
    fun `PIN oublie - nouveau PIN, anciennes sessions fermees, envois suspendus 24 h`() {
        val alice = register("+243970000501")
        val bob = register("+243970000502")
        topupConfirmed(alice, 5_000, "t-1")

        requestCode(alice.phone).andExpect { status { isAccepted() } }
        val body = reset(alice.phone, lastSmsCode(alice.phone), "7391").andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
        }.body()
        val fresh = Session(body["accessToken"].asText(), alice.customerId, alice.walletId, alice.phone)

        mvc.get("/v1/me") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isUnauthorized() } }
        loginRequest(alice.phone, "2580").andExpect { status { isUnauthorized() } }

        transfer(fresh, bob.phone, 1_000, "e-1", pin = "7391").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PIN_RECENTLY_RESET") }
        }
        mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${fresh.token}") }.andExpect {
            jsonPath("$.outgoingBlockedUntil") { exists() }
        }

        testClock.advance(Duration.ofHours(25))
        val later = login(alice.phone, "7391")
        transfer(later, bob.phone, 1_000, "e-2", pin = "7391").andExpect { status { isCreated() } }
    }

    @Test
    fun `un numero inconnu recoit la meme reponse mais aucun SMS`() {
        requestCode("+243970000599").andExpect { status { isAccepted() } }
        assertNull(mvc.get("/v1/simulators/sms/+243970000599").body()["text"]?.takeUnless { it.isNull }?.asText())
    }

    @Test
    fun `le code d'inscription ne sert pas a changer le PIN`() {
        val alice = register("+243970000503")
        reset(alice.phone, "123456", "7391").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("OTP_INVALID") }
        }
        requestCode(alice.phone)
        val wrong = if (lastSmsCode(alice.phone) == "000000") "111111" else "000000"
        reset(alice.phone, wrong, "7391").andExpect { jsonPath("$.code") { value("OTP_INVALID") } }
    }

    @Test
    fun `changer le PIN debloque un PIN bloque`() {
        val alice = register("+243970000504")
        repeat(5) { loginRequest(alice.phone, "9999") }
        loginRequest(alice.phone, "2580").andExpect { status { isLocked() } }

        requestCode(alice.phone)
        reset(alice.phone, lastSmsCode(alice.phone), "7391").andExpect { status { isOk() } }
        loginRequest(alice.phone, "7391").andExpect { status { isOk() } }
    }

    @Test
    fun `le nouveau PIN doit respecter les regles`() {
        val alice = register("+243970000505")
        requestCode(alice.phone)
        reset(alice.phone, lastSmsCode(alice.phone), "1111").andExpect { status { isUnprocessableEntity() } }
    }
}
