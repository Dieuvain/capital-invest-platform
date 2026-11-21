package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BiometricLoginApiTest : ApiTest() {

    private fun enable(s: Session, pin: String = "2580") = mvc.post("/v1/me/security/biometric") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"pin":"$pin"}"""
    }

    private fun enabledKey(s: Session): String =
        enable(s).andExpect { status { isCreated() } }.body()["key"].asText()

    private fun biometricLogin(phone: String, key: String, deviceId: String = "test-device") = mvc.post("/v1/auth/biometric") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","deviceId":"$deviceId","key":"$key","deviceName":"Tecno Spark 10"}"""
    }

    private fun loginOn(phone: String, deviceId: String) = mvc.post("/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","pin":"2580","deviceId":"$deviceId"}"""
    }.andExpect { status { isOk() } }.body().let { Session(it["accessToken"].asText(), "", "", phone) }

    @Test
    fun `l'empreinte activee avec le PIN ouvre une session, le PIN reste exige pour envoyer`() {
        val alice = register("+243970000901")
        val bob = register("+243970000902")
        topupConfirmed(alice, 5_000, "t-1")

        enable(alice, "1111").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("INVALID_CREDENTIALS") } }
        val key = enabledKey(alice)

        val body = biometricLogin(alice.phone, key).andExpect { status { isOk() } }.body()
        val session = Session(body["accessToken"].asText(), alice.customerId, alice.walletId, alice.phone)
        assertEquals(alice.customerId, body["customer"]["customer"]["id"].asText())
        assertEquals(5_000, balance(session))

        // L'empreinte ouvre l'app ; un envoi demande toujours le PIN.
        transfer(session, bob.phone, 1_000, "e-1", pin = "0000").andExpect { status { isUnauthorized() } }
        transfer(session, bob.phone, 1_000, "e-2").andExpect { status { isCreated() } }
    }

    @Test
    fun `la cle ne vaut que pour son numero et son telephone`() {
        val alice = register("+243970000903")
        val bob = register("+243970000904")
        val key = enabledKey(alice)

        biometricLogin(alice.phone, key, deviceId = "autre-tel").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("BIOMETRIC_INVALID") } }
        biometricLogin(bob.phone, key).andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("BIOMETRIC_INVALID") } }
        biometricLogin("+243970000999", key).andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("BIOMETRIC_INVALID") } }
        biometricLogin(alice.phone, key.reversed()).andExpect { status { isUnauthorized() } }
        biometricLogin(alice.phone, key).andExpect { status { isOk() } }
    }

    @Test
    fun `reactiver remplace la cle, desactiver la retire`() {
        val alice = register("+243970000905")
        val first = enabledKey(alice)
        val second = enabledKey(alice)
        assertNotEquals(first, second)
        biometricLogin(alice.phone, first).andExpect { status { isUnauthorized() } }
        biometricLogin(alice.phone, second).andExpect { status { isOk() } }

        mvc.delete("/v1/me/security/biometric") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isNoContent() } }
        biometricLogin(alice.phone, second).andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("BIOMETRIC_INVALID") } }
        // Le PIN marche toujours.
        login(alice.phone)
    }

    @Test
    fun `changer son PIN retire l'empreinte des autres telephones, pas de celui-ci`() {
        val alice = register("+243970000906")
        val here = enabledKey(alice)
        val other = loginOn(alice.phone, "tel-2")
        val otherKey = enabledKey(other)

        mvc.post("/v1/me/security/pin") {
            header("Authorization", "Bearer ${alice.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"currentPin":"2580","newPin":"7391"}"""
        }.andExpect { status { isNoContent() } }

        biometricLogin(alice.phone, here).andExpect { status { isOk() } }
        biometricLogin(alice.phone, otherKey, deviceId = "tel-2").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `deconnecter les autres telephones leur retire aussi l'empreinte`() {
        val alice = register("+243970000907")
        val other = loginOn(alice.phone, "tel-2")
        val otherKey = enabledKey(other)
        mvc.post("/v1/me/security/sessions/sign-out-others") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }
        biometricLogin(alice.phone, otherKey, deviceId = "tel-2").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `PIN oublie et blocage retirent l'empreinte partout`() {
        val alice = register("+243970000908")
        val key = enabledKey(alice)
        mvc.post("/v1/auth/pin-reset/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"${alice.phone}"}"""
        }.andExpect { status { isAccepted() } }
        val reset = mvc.post("/v1/auth/pin-reset") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"${alice.phone}","code":"${lastSmsCode(alice.phone)}","newPin":"7391","deviceId":"test-device"}"""
        }.andExpect { status { isOk() } }.body()
        biometricLogin(alice.phone, key).andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("BIOMETRIC_INVALID") } }

        val after = Session(reset["accessToken"].asText(), alice.customerId, alice.walletId, alice.phone)
        // Réactivée avec le nouveau PIN, puis le client bloque son compte : la clé tombe aussi.
        val key2 = enable(after, "7391").andExpect { status { isCreated() } }.body()["key"].asText()
        mvc.post("/v1/me/security/block") {
            header("Authorization", "Bearer ${after.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"pin":"7391"}"""
        }.andExpect { status { isNoContent() } }
        biometricLogin(alice.phone, key2).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `sans session, impossible d'activer l'empreinte`() {
        mvc.post("/v1/me/security/biometric") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"pin":"2580"}"""
        }.andExpect { status { isUnauthorized() } }
    }
}
