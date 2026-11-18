package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

class PaymentsApiTest : ApiTest() {

    private fun send(from: Session, toPhone: String, amount: Long, key: String, pin: String = "2580") =
        mvc.post("/v1/transfers") {
            header("Authorization", "Bearer ${from.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"toPhone":"$toPhone","amountMinor":$amount,"pin":"$pin"}"""
        }

    @Test
    fun `parcours complet - inscription, recharge simulee, envoi`() {
        val alice = register("+243970000101")
        val bob = register("+243970000102")

        val topup = mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "app-topup-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"MPESA","amountMinor":5000}"""
        }.andExpect { status { isAccepted() } }.body()
        assertEquals("PENDING", topup["status"].asText())
        assertEquals(0, balance(alice))

        // L'opérateur confirme deux fois (doublon de webhook) : un seul crédit.
        repeat(2) {
            mvc.post("/v1/simulators/mobile-money/callbacks") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"topupId":"${topup["id"].asText()}","success":true,"operatorReference":"MP123"}"""
            }.andExpect { status { isOk() } }
        }
        assertEquals(5000, balance(alice))

        // Frais de 1 % : 12,50 $ envoyés coûtent 0,13 $ de plus ; Bob reçoit le montant entier.
        send(alice, bob.phone, 1250, "app-transfer-1").andExpect {
            status { isCreated() }
            jsonPath("$.feeMinor") { value(13) }
            jsonPath("$.totalDebitedMinor") { value(1263) }
        }
        assertEquals(3737, balance(alice))
        assertEquals(1250, balance(bob))
    }

    @Test
    fun `un envoi superieur au solde renvoie une erreur lisible`() {
        val alice = register("+243970000103")
        register("+243970000104")
        send(alice, "+243970000104", 100, "t-1").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INSUFFICIENT_FUNDS") }
        }
    }

    @Test
    fun `un envoi avec un mauvais PIN est refuse et rien ne bouge`() {
        val alice = register("+243970000105")
        val bob = register("+243970000106")
        topupConfirmed(alice, 1000, "k-1")
        send(alice, bob.phone, 500, "t-2", pin = "9999").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
        assertEquals(1000, balance(alice))
        assertEquals(0, balance(bob))
    }

    @Test
    fun `une recharge echouee ne credite rien`() {
        val alice = register("+243970000107")
        val topup = mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "app-topup-2")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"AIRTEL","amountMinor":2000}"""
        }.body()
        mvc.post("/v1/simulators/mobile-money/callbacks") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topupId":"${topup["id"].asText()}","success":false}"""
        }.andExpect { jsonPath("$.status") { value("FAILED") } }
        assertEquals(0, balance(alice))
    }

    @Test
    fun `un client ne voit pas la recharge d'un autre`() {
        val alice = register("+243970000108")
        val bob = register("+243970000109")
        val topup = mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "app-topup-3")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"ORANGE","amountMinor":2000}"""
        }.body()
        mvc.get("/v1/topups/${topup["id"].asText()}") { header("Authorization", "Bearer ${bob.token}") }
            .andExpect { status { isNotFound() } }
    }
}
