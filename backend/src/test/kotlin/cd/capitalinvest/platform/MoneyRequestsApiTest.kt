package cd.capitalinvest.platform

import cd.capitalinvest.platform.auth.SimulatedSmsGateway
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals

/** Demander de l'argent : le client sollicité paie avec son PIN, ou refuse. */
class MoneyRequestsApiTest : ApiTest() {

    @Autowired lateinit var sms: SimulatedSmsGateway

    @BeforeEach
    fun clearSms() = sms.clear()

    private fun ask(from: Session, payerPhone: String, amount: Long, key: String, note: String? = null) = mvc.post("/v1/money-requests") {
        header("Authorization", "Bearer ${from.token}")
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"payerPhone":"$payerPhone","amountMinor":$amount${note?.let { ",\"note\":\"$it\"" } ?: ""}}"""
    }

    private fun list(s: Session): JsonNode =
        mvc.get("/v1/money-requests") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()

    private fun act(s: Session, id: String, action: String, pin: String? = null) = mvc.post("/v1/money-requests/$id/$action") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = pin?.let { """{"pin":"$it"}""" } ?: "{}"
    }

    @Test
    fun `demande, SMS au client sollicite, paiement par PIN une seule fois`() {
        val grace = register("+243970000701")
        val patrick = register("+243970000702")
        topupConfirmed(patrick, 5_000, "rq-top")
        sms.clear()

        val r = ask(grace, "0970 000 702", 2_000, "rq-1", "Frais scolaires").andExpect { status { isCreated() } }.body()
        assertEquals("OUTGOING", r["direction"].asText())
        assertEquals("PENDING", r["status"].asText())
        assertEquals("+243970000702", r["counterpartyPhone"].asText())
        // Double appui : la même demande, pas une seconde.
        assertEquals(r["id"], ask(grace, "+243970000702", 2_000, "rq-1").andExpect { status { isOk() } }.body()["id"])
        assertEquals(
            listOf("Capital Invest: Client +243970000701 vous demande 20,00 \$. Ouvrez l'app pour payer ou refuser. Ne payez que si vous le connaissez."),
            sms.messages("+243970000702"),
        )

        val incoming = list(patrick)
        assertEquals(1, incoming["toPay"].size())
        assertEquals("INCOMING", incoming["toPay"][0]["direction"].asText())
        assertEquals("Frais scolaires", incoming["toPay"][0]["note"].asText())
        val id = r["id"].asText()

        // Seul le client sollicité paie, et avec son PIN.
        act(grace, id, "pay", "2580").andExpect { status { isNotFound() } }
        act(patrick, id, "pay", "0000").andExpect { status { isUnauthorized() } }
        val paid = act(patrick, id, "pay", "2580").andExpect { status { isOk() } }.body()
        assertEquals("PAID", paid["request"]["status"].asText())
        assertEquals(2_000, paid["transfer"]["amountMinor"].asLong())
        val fee = paid["transfer"]["feeMinor"].asLong()
        assertEquals(5_000 - 2_000 - fee, balance(patrick))
        assertEquals(2_000, balance(grace))
        // Rejouer le paiement ne paie pas deux fois.
        act(patrick, id, "pay", "2580").andExpect { status { isOk() } }
        assertEquals(2_000, balance(grace))
        act(patrick, id, "decline").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("REQUEST_CLOSED") } }

        val mine = list(grace)
        assertEquals(0, mine["toPay"].size())
        assertEquals("PAID", mine["recent"][0]["status"].asText())
        assertEquals(paid["transfer"]["transaction"]["id"], mine["recent"][0]["transactionId"])
    }

    @Test
    fun `refus, annulation, expiration et limites`() {
        val grace = register("+243970000711")
        val patrick = register("+243970000712")
        sms.clear()
        ask(grace, grace.phone, 100, "self").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("SAME_ACCOUNT") } }
        ask(grace, "+243970000999", 100, "nobody").andExpect { status { isNotFound() }; jsonPath("$.code") { value("RECIPIENT_NOT_FOUND") } }

        val declined = ask(grace, patrick.phone, 1_000, "d").andExpect { status { isCreated() } }.body()["id"].asText()
        act(patrick, declined, "decline").andExpect { status { isOk() }; jsonPath("$.status") { value("DECLINED") } }
        val inbox = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${grace.token}") }.body()
        assertEquals("Demande refusée", inbox["items"][0]["title"].asText())
        assertEquals(listOf<String>(), sms.messages(grace.phone), "le refus ne part pas par SMS")

        val cancelled = ask(grace, patrick.phone, 1_000, "c").andExpect { status { isCreated() } }.body()["id"].asText()
        act(patrick, cancelled, "cancel").andExpect { status { isNotFound() } }
        act(grace, cancelled, "cancel").andExpect { status { isOk() }; jsonPath("$.status") { value("CANCELLED") } }

        // 3 demandes en attente vers le même client au plus.
        val pending = (1..3).map { ask(grace, patrick.phone, 100, "p$it").andExpect { status { isCreated() } }.body()["id"].asText() }
        ask(grace, patrick.phone, 100, "p4").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("REQUEST_LIMIT") } }

        // Sans réponse en 7 jours, la demande expire et ne se paie plus.
        testClock.advance(Duration.ofDays(8))
        val later = login(patrick.phone)
        assertEquals(0, list(later)["toPay"].size())
        act(later, pending[0], "pay", "2580").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("REQUEST_EXPIRED") } }
        assertEquals("EXPIRED", list(later)["recent"].first { it["id"].asText() == pending[0] }["status"].asText())
    }
}
