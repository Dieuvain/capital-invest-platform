package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration

class AuthApiTest : ApiTest() {

    private fun login(phone: String, pin: String) = mvc.post("/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","pin":"$pin","deviceId":"tel-1"}"""
    }

    @Test
    fun `sans jeton l'API refuse l'acces`() {
        mvc.get("/v1/me/balance").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("AUTH_REQUIRED") }
        }
        mvc.get("/v1/me/balance") { header("Authorization", "Bearer faux-jeton") }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("SESSION_INVALID") }
        }
    }

    @Test
    fun `inscription puis connexion avec le bon PIN`() {
        val s = register("+243970000201")
        mvc.get("/v1/me") { header("Authorization", "Bearer ${s.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.customer.phone") { value("+243970000201") }
            jsonPath("$.customer.kycLevel") { value(1) }
        }
        login(s.phone, "2580").andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
        }
    }

    @Test
    fun `un mauvais code SMS bloque l'inscription`() {
        mvc.post("/v1/auth/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000202"}"""
        }
        val wrong = if (lastSmsCode("+243970000202") == "000000") "111111" else "000000"
        mvc.post("/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000202","code":"$wrong","fullName":"X","pin":"2580","deviceId":"d"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("OTP_INVALID") }
        }
    }

    @Test
    fun `un code SMS expire apres 5 minutes`() {
        mvc.post("/v1/auth/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000203"}"""
        }
        val code = lastSmsCode("+243970000203")
        testClock.advance(Duration.ofMinutes(6))
        mvc.post("/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000203","code":"$code","fullName":"X","pin":"2580","deviceId":"d"}"""
        }.andExpect { jsonPath("$.code") { value("OTP_INVALID") } }
    }

    @Test
    fun `plus de 3 codes en 10 minutes est refuse`() {
        repeat(3) {
            mvc.post("/v1/auth/otp") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"phone":"+243970000204"}"""
            }.andExpect { status { isAccepted() } }
        }
        mvc.post("/v1/auth/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000204"}"""
        }.andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `un PIN trop simple est refuse`() {
        mvc.post("/v1/auth/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000205"}"""
        }
        mvc.post("/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+243970000205","code":"${lastSmsCode("+243970000205")}","fullName":"X","pin":"1234","deviceId":"d"}"""
        }.andExpect { jsonPath("$.code") { value("PIN_TOO_SIMPLE") } }
    }

    @Test
    fun `5 PIN faux bloquent le compte 30 minutes, meme avec le bon PIN`() {
        val s = register("+243970000206")
        repeat(4) { login(s.phone, "9999").andExpect { status { isUnauthorized() } } }
        login(s.phone, "9999").andExpect { status { isLocked() } }
        login(s.phone, "2580").andExpect { status { isLocked() } }
        testClock.advance(Duration.ofMinutes(31))
        login(s.phone, "2580").andExpect { status { isOk() } }
    }

    @Test
    fun `la session expire apres 30 minutes d'inactivite`() {
        val s = register("+243970000207")
        testClock.advance(Duration.ofMinutes(29))
        mvc.get("/v1/me") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }
        testClock.advance(Duration.ofMinutes(31))
        mvc.get("/v1/me") { header("Authorization", "Bearer ${s.token}") }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("SESSION_EXPIRED") }
        }
    }

    @Test
    fun `apres deconnexion le jeton ne marche plus`() {
        val s = register("+243970000208")
        mvc.post("/v1/auth/logout") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isNoContent() } }
        mvc.get("/v1/me") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `un numero hors RDC est refuse`() {
        mvc.post("/v1/auth/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+33612345678"}"""
        }.andExpect { status { isBadRequest() } }
    }
}
