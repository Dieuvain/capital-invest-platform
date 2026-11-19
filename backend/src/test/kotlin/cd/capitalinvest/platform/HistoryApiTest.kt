package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Historique des opérations et reçus : une ligne par opération, montants et frais lisibles, rien de l'autre client. */
class HistoryApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private fun history(s: Session, query: String = ""): JsonNode =
        mvc.get("/v1/me/history$query") { header("Authorization", "Bearer ${s.token}") }
            .andExpect { status { isOk() } }.body()

    private fun kinds(page: JsonNode) = page["items"].map { it["kind"].asText() }

    private fun withdraw(s: Session, amount: Long, key: String) = mvc.post("/v1/withdrawals") {
        header("Authorization", "Bearer ${s.token}")
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"operator":"AIRTEL","amountMinor":$amount,"pin":"2580"}"""
    }.andExpect { status { isAccepted() } }.body()["id"].asText()

    private fun payout(id: String, success: Boolean) = mvc.post("/v1/simulators/mobile-money/payouts") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"withdrawalId":"$id","success":$success}"""
    }.andExpect { status { isOk() } }

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    @Test
    fun `chaque operation apparait une fois, avec son montant, ses frais et son effet sur le solde`() {
        val alice = register("+243970000501")
        val bob = register("+243970000502")
        topupConfirmed(alice, 10000, "h-top-1")
        testClock.advance(Duration.ofMinutes(1))
        mvc.post("/v1/transfers") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "h-tr-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"toPhone":"${bob.phone}","amountMinor":2000,"note":"Loyer de novembre","pin":"2580"}"""
        }.andExpect { status { isCreated() } }
        testClock.advance(Duration.ofMinutes(1))
        val failed = withdraw(alice, 1000, "h-w-1")
        payout(failed, success = false)
        testClock.advance(Duration.ofMinutes(1))
        withdraw(alice, 3000, "h-w-2") // pas encore versé

        val page = history(alice)
        assertEquals(listOf("WITHDRAWAL", "WITHDRAWAL_REFUND", "WITHDRAWAL", "TRANSFER_SENT", "TOPUP"), kinds(page))
        val (pending, refund, refused, sent, topup) = page["items"].toList()

        assertEquals("PENDING", pending["status"].asText())
        assertEquals(3000, pending["amountMinor"].asLong())
        assertEquals(45, pending["feeMinor"].asLong())
        assertEquals(-3045, pending["netMinor"].asLong())
        assertEquals("AIRTEL", pending["operator"].asText())
        assertEquals("+243970000501", pending["msisdn"].asText())

        assertEquals("FAILED", refused["status"].asText())
        assertEquals(1025, refund["netMinor"].asLong())
        assertEquals(25, refund["feeMinor"].asLong(), "les frais rendus sont montrés")

        assertEquals(2000, sent["amountMinor"].asLong())
        assertEquals(20, sent["feeMinor"].asLong())
        assertEquals(-2020, sent["netMinor"].asLong())
        assertEquals("Client +243970000502", sent["counterpartyName"].asText())
        assertEquals("+243 97• ••• 502", sent["counterpartyPhone"].asText())
        assertEquals("Loyer de novembre", sent["note"].asText())

        assertEquals("MPESA", topup["operator"].asText())
        assertEquals(10000, topup["netMinor"].asLong())
        // La somme des effets donne le solde.
        assertEquals(balance(alice), page["items"].sumOf { it["netMinor"].asLong() })

        // Le destinataire voit l'argent reçu, sans les frais payés par l'expéditeur.
        val received = history(bob)["items"].single()
        assertEquals("TRANSFER_RECEIVED", received["kind"].asText())
        assertEquals(2000, received["amountMinor"].asLong())
        assertEquals(0, received["feeMinor"].asLong())
        assertEquals("Client +243970000501", received["counterpartyName"].asText())
    }

    @Test
    fun `filtres par type, sens et periode, et pagination stable`() {
        val alice = register("+243970000503")
        val bob = register("+243970000504")
        repeat(5) {
            topupConfirmed(alice, 1000, "h-top-p-$it")
            testClock.advance(Duration.ofMinutes(1))
        }
        transfer(alice, bob.phone, 500, "h-tr-p").andExpect { status { isCreated() } }

        assertEquals(List(5) { "TOPUP" }, kinds(history(alice, "?category=TOPUP")))
        assertEquals(listOf("TRANSFER_SENT"), kinds(history(alice, "?direction=OUT")))
        assertEquals(5, history(alice, "?direction=IN")["items"].size())

        val first = history(alice, "?limit=4")
        assertEquals(4, first["items"].size())
        // Une opération qui arrive entre deux pages ne décale pas la suite.
        topupConfirmed(alice, 1000, "h-top-p-new")
        val second = history(alice, "?limit=4&cursor=${first["nextCursor"].asText()}")
        assertEquals(2, second["items"].size())
        assertTrue(second["nextCursor"].isNull)
        val ids = (first["items"].toList() + second["items"].toList()).map { it["id"].asText() }
        assertEquals(ids.toSet().size, 6)

        val today = LocalDate.now(testClock.withZone(ZoneId.of("Africa/Lubumbashi")))
        assertEquals(7, history(alice, "?from=$today&to=$today")["items"].size())
        assertEquals(0, history(alice, "?from=${today.plusDays(1)}")["items"].size())
        mvc.get("/v1/me/history?from=$today&to=${today.minusDays(1)}") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { jsonPath("$.code") { value("INVALID_PERIOD") } }
        mvc.get("/v1/me/history?cursor=n-importe-quoi") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { jsonPath("$.code") { value("INVALID_CURSOR") } }
    }

    @Test
    fun `le recu est celui du client, et personne d'autre ne peut l'ouvrir`() {
        val alice = register("+243970000505")
        val eve = register("+243970000506")
        topupConfirmed(alice, 5000, "h-top-r")
        val id = history(alice)["items"][0]["id"].asText()

        mvc.get("/v1/me/history/$id") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.item.kind") { value("TOPUP") }
            jsonPath("$.item.receiptNumber") { value(org.hamcrest.Matchers.matchesPattern("CI-\\d{8}-[0-9A-F]{6}")) }
            jsonPath("$.holderName") { value("Client +243970000505") }
            jsonPath("$.holderPhone") { value("+243970000505") }
        }
        mvc.get("/v1/me/history/$id") { header("Authorization", "Bearer ${eve.token}") }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("OPERATION_NOT_FOUND") }
        }
        mvc.get("/v1/me/history").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `la conformite retrouve un client par son numero et lit ses operations, et c'est trace`() {
        val alice = register("+243970000507")
        val bob = register("+243970000508")
        topupConfirmed(alice, 5000, "h-top-b")
        transfer(alice, bob.phone, 1000, "h-tr-b").andExpect { status { isCreated() } }

        val kyc = agent(AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/customers?phone=%2B243970000507") { header("Authorization", "Bearer $kyc") }
            .andExpect { status { isForbidden() } }

        val compliance = agent(AgentRole.COMPLIANCE)
        val customer = mvc.get("/v1/backoffice/customers?phone=243970000507") { header("Authorization", "Bearer $compliance") }
            .andExpect {
                status { isOk() }
                jsonPath("$.fullName") { value("Client +243970000507") }
                jsonPath("$.balanceMinor") { value(3990) }
            }.body()
        val page = mvc.get("/v1/backoffice/customers/${customer["id"].asText()}/history") { header("Authorization", "Bearer $compliance") }
            .andExpect { status { isOk() } }.body()
        // Côté back-office, le numéro de l'autre client est complet.
        assertEquals("+243970000508", page["items"][0]["counterpartyPhone"].asText())
        val tx = page["items"][0]["id"].asText()
        mvc.get("/v1/backoffice/customers/${customer["id"].asText()}/history/$tx") { header("Authorization", "Bearer $compliance") }
            .andExpect { jsonPath("$.item.kind") { value("TRANSFER_SENT") } }

        mvc.get("/v1/backoffice/customers?phone=%2B243999999999") { header("Authorization", "Bearer $compliance") }
            .andExpect { status { isNotFound() } }
        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_type = 'CUSTOMER'").query(String::class.java).list()
        assertEquals(listOf("CUSTOMER_VIEWED"), audit)
        assertNull(page["items"][0]["failureMessage"].textValue())
    }
}
