package cd.capitalinvest.platform

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Outils communs aux tests d'API : inscription par code SMS simulé, puis appels authentifiés. */
@AutoConfigureMockMvc
abstract class ApiTest : DatabaseTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    data class Session(val token: String, val customerId: String, val walletId: String, val phone: String)

    fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    fun lastSmsCode(phone: String): String {
        val text = mvc.get("/v1/simulators/sms/$phone").body()["text"].asText()
        return Regex("[0-9]{6}").find(text)!!.value
    }

    fun register(phone: String, pin: String = "2580"): Session {
        mvc.post("/v1/auth/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"$phone"}"""
        }.andExpect { status { isAccepted() } }
        val body = mvc.post("/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"$phone","code":"${lastSmsCode(phone)}","fullName":"Client $phone","language":"sw","pin":"$pin","deviceId":"test-device"}"""
        }.andExpect { status { isCreated() } }.body()
        return Session(
            body["accessToken"].asText(),
            body["customer"]["customer"]["id"].asText(),
            body["customer"]["walletAccountId"].asText(),
            phone,
        )
    }

    fun balance(s: Session): Long =
        mvc.get("/v1/me/balance") { header("Authorization", "Bearer ${s.token}") }
            .andExpect { status { isOk() } }.body()["balanceMinor"].asLong()

    fun topupConfirmed(s: Session, amountMinor: Long, key: String) {
        val topup = mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"MPESA","amountMinor":$amountMinor}"""
        }.andExpect { status { isAccepted() } }.body()
        mvc.post("/v1/simulators/mobile-money/callbacks") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topupId":"${topup["id"].asText()}","success":true}"""
        }.andExpect { status { isOk() } }
    }

    fun transfer(from: Session, toPhone: String, amountMinor: Long, key: String, pin: String = "2580") =
        mvc.post("/v1/transfers") {
            header("Authorization", "Bearer ${from.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"toPhone":"$toPhone","amountMinor":$amountMinor,"pin":"$pin"}"""
        }

    fun setKycLevel(s: Session, level: Int) {
        jdbc.sql("UPDATE customer SET kyc_level = :l WHERE id = :id::uuid").param("l", level).param("id", s.customerId).update()
    }

    fun loginRequest(phone: String, pin: String) = mvc.post("/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"phone":"$phone","pin":"$pin","deviceId":"tel-1"}"""
    }

    fun login(phone: String, pin: String = "2580"): Session {
        val body = loginRequest(phone, pin).andExpect { status { isOk() } }.body()
        return Session(body["accessToken"].asText(), body["customer"]["customer"]["id"].asText(), body["customer"]["walletAccountId"].asText(), phone)
    }
}
