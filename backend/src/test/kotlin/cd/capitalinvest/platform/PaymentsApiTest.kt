package cd.capitalinvest.platform

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@AutoConfigureMockMvc
class PaymentsApiTest : DatabaseTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private fun openCustomer(phone: String): JsonNode =
        mvc.post("/v1/customers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"$phone","fullName":"Client $phone","language":"sw"}"""
        }.andExpect { status { isCreated() } }.body()

    private fun balance(walletId: String): Long =
        mvc.get("/v1/accounts/$walletId/balance").andExpect { status { isOk() } }.body()["balanceMinor"].asLong()

    @Test
    fun `parcours complet - ouverture, recharge simulee, envoi`() {
        val alice = openCustomer("+243970000101")
        val bob = openCustomer("+243970000102")
        val aliceWallet = alice["walletAccountId"].asText()
        val bobWallet = bob["walletAccountId"].asText()

        val topup = mvc.post("/v1/topups") {
            header("Idempotency-Key", "app-topup-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId":"${alice["customer"]["id"].asText()}","operator":"MPESA","amountMinor":5000}"""
        }.andExpect { status { isAccepted() } }.body()
        assertEquals("PENDING", topup["status"].asText())
        assertEquals(0, balance(aliceWallet))

        // L'opérateur confirme deux fois (doublon de webhook) : un seul crédit.
        repeat(2) {
            mvc.post("/v1/simulators/mobile-money/callbacks") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"topupId":"${topup["id"].asText()}","success":true,"operatorReference":"MP123"}"""
            }.andExpect { status { isOk() } }
        }
        assertEquals(5000, balance(aliceWallet))

        mvc.post("/v1/transfers") {
            header("Idempotency-Key", "app-transfer-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"fromCustomerId":"${alice["customer"]["id"].asText()}","toPhone":"+243970000102","amountMinor":1250}"""
        }.andExpect { status { isCreated() } }

        assertEquals(3750, balance(aliceWallet))
        assertEquals(1250, balance(bobWallet))
    }

    @Test
    fun `un envoi superieur au solde renvoie une erreur lisible`() {
        val alice = openCustomer("+243970000103")
        openCustomer("+243970000104")
        mvc.post("/v1/transfers") {
            header("Idempotency-Key", "t-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"fromCustomerId":"${alice["customer"]["id"].asText()}","toPhone":"+243970000104","amountMinor":100}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INSUFFICIENT_FUNDS") }
        }
    }

    @Test
    fun `un numero hors RDC est refuse a l'ouverture`() {
        mvc.post("/v1/customers") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"+33612345678","fullName":"Test"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `une recharge echouee ne credite rien`() {
        val alice = openCustomer("+243970000105")
        val topup = mvc.post("/v1/topups") {
            header("Idempotency-Key", "app-topup-2")
            contentType = MediaType.APPLICATION_JSON
            content = """{"customerId":"${alice["customer"]["id"].asText()}","operator":"AIRTEL","amountMinor":2000}"""
        }.body()
        mvc.post("/v1/simulators/mobile-money/callbacks") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topupId":"${topup["id"].asText()}","success":false}"""
        }.andExpect { jsonPath("$.status") { value("FAILED") } }
        assertEquals(0, balance(alice["walletAccountId"].asText()))
    }
}
