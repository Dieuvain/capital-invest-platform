package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import cd.capitalinvest.platform.payments.TopupService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Recharges par le simulateur : écarts de montant, relances, recharges à vérifier et rapprochement. */
class MobileMoneyApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var topupService: TopupService

    private fun agent(email: String, role: AgentRole): String {
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.andExpect { status { isOk() } }.body()["accessToken"].asText()
    }

    private fun startTopup(s: Session, amount: Long, key: String, operator: String = "MPESA") =
        mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"$operator","amountMinor":$amount}"""
        }.andExpect { status { isAccepted() } }.body()["id"].asText()

    private fun simulate(id: String, success: Boolean, amount: Long? = null) =
        mvc.post("/v1/simulators/mobile-money/callbacks") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topupId":"$id","success":$success${amount?.let { ""","amountMinor":$it""" } ?: ""}}"""
        }.andExpect { status { isOk() } }.body()

    private fun today() = LocalDate.now(testClock.withZone(ZoneId.of("Africa/Lubumbashi")))

    @Test
    fun `la recharge vue par le client ne montre rien d'interne, et un refus donne une raison`() {
        val alice = register("+243970000201")
        val id = startTopup(alice, 2000, "mm-1")
        val failed = simulate(id, success = false)
        assertEquals("FAILED", failed["status"].asText())
        assertEquals("SIMULATED_FAILURE", failed["failureCode"].asText())

        val seen = mvc.get("/v1/topups/$id") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals("Paiement refusé par l'opérateur (simulation)", seen["failureMessage"].asText())
        listOf("settlementCode", "provider", "msisdn", "reviewReason", "customerId", "idempotencyKey", "ledgerTransactionId")
            .forEach { assertFalse(seen.has(it), "$it ne doit pas être exposé") }
    }

    @Test
    fun `un montant different chez l'operateur met la recharge a verifier, puis l'agent credite le montant recu`() {
        val alice = register("+243970000202")
        val id = startTopup(alice, 5000, "mm-2")
        assertEquals("REVIEW", simulate(id, success = true, amount = 4000)["status"].asText())
        assertEquals(0, balance(alice))

        val compliance = agent("conformite@capitalinvest.cd", AgentRole.COMPLIANCE)
        mvc.get("/v1/backoffice/topups/review") { header("Authorization", "Bearer $compliance") }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].reason") { value("Montant reçu chez l'opérateur : 4000 USD, attendu 5000 USD") }
            jsonPath("$[0].customerPhone") { value("+243970000202") }
        }
        val kyc = agent("kyc@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/topups/review") { header("Authorization", "Bearer $kyc") }.andExpect { status { isForbidden() } }

        // Clore en échec est refusé : l'opérateur dit que l'argent est arrivé.
        mvc.post("/v1/backoffice/topups/$id/resolve") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"CLOSE","note":"test"}"""
        }.andExpect { jsonPath("$.code") { value("PROVIDER_COMPLETED") } }

        mvc.post("/v1/backoffice/topups/$id/resolve") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"CREDIT","note":"Le client a confirmé avoir envoyé 40 $"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONFIRMED") }
            jsonPath("$.amountMinor") { value(4000) }
        }
        assertEquals(4000, balance(alice))
        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_id = :id::uuid").param("id", id).query(String::class.java).list()
        assertEquals(listOf("TOPUP_REVIEW_CREDITED"), audit)
    }

    @Test
    fun `les relances espacent les verifications, puis une recharge sans reponse passe a verifier apres 24 h`() {
        val alice = register("+243970000203")
        val id = startTopup(alice, 1000, "mm-3")
        val interval = {
            jdbc.sql("SELECT EXTRACT(EPOCH FROM next_check_at - last_checked_at)::int FROM topup WHERE id = :id::uuid")
                .param("id", id).query(Int::class.java).single()
        }

        assertEquals(0, topupService.checkDue(), "première vérification après 10 s seulement")
        testClock.advance(Duration.ofSeconds(11))
        assertEquals(1, topupService.checkDue())
        assertEquals(10, interval())
        testClock.advance(Duration.ofSeconds(11))
        assertEquals(1, topupService.checkDue())
        assertEquals(20, interval(), "l'intervalle double à chaque relance")

        testClock.advance(Duration.ofHours(25))
        topupService.checkDue()
        assertEquals("REVIEW", topupService.get(java.util.UUID.fromString(id)).status.name)
        assertEquals(0, topupService.checkDue())
    }

    @Test
    fun `le rapprochement trouve un paiement encaisse mais non credite, et l'agent le credite`() {
        val alice = register("+243970000204")
        val ok = startTopup(alice, 1500, "mm-4a")
        simulate(ok, success = true)
        val failed = startTopup(alice, 2500, "mm-4b", operator = "AIRTEL")
        simulate(failed, success = false)
        val pending = startTopup(alice, 700, "mm-4c", operator = "ORANGE")
        // Plus tard, l'opérateur se ravise : le paiement « échoué » a finalement été encaissé.
        simulateLater(failed)

        val admin = agent("admin@capitalinvest.cd", AgentRole.ADMIN)
        mvc.post("/v1/backoffice/reconciliation/runs") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"businessDate":"${today()}"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.checked") { value(3) }
            jsonPath("$.matched") { value(1) }
            jsonPath("$.discrepancies") { value(2) }
        }
        val items = mvc.get("/v1/backoffice/reconciliation/items") { header("Authorization", "Bearer $admin") }.body()
        val byTopup = items.associateBy { it["topupId"].asText() }
        assertEquals("CONFIRMED_NOT_CREDITED", byTopup[failed]!!["kind"].asText())
        assertEquals("STILL_PENDING", byTopup[pending]!!["kind"].asText())

        // Relancer la même journée ne duplique rien.
        mvc.post("/v1/backoffice/reconciliation/runs") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"businessDate":"${today()}"}"""
        }.andExpect { jsonPath("$.discrepancies") { value(2) } }

        val itemId = mvc.get("/v1/backoffice/reconciliation/items") { header("Authorization", "Bearer $admin") }.body()
            .first { it["topupId"].asText() == failed }["id"].asText()
        mvc.post("/v1/backoffice/reconciliation/items/$itemId/resolve") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"CREDIT","note":"Confirmé par Airtel"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.resolvedByName") { value("Agent admin@capitalinvest.cd") }
        }
        assertEquals(1500 + 2500, balance(alice))
        mvc.post("/v1/backoffice/reconciliation/items/$itemId/resolve") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"CREDIT","note":"encore"}"""
        }.andExpect { status { isConflict() } }
        assertEquals(1500 + 2500, balance(alice))

        mvc.get("/v1/backoffice/reconciliation/runs") { header("Authorization", "Bearer $admin") }.andExpect {
            jsonPath("$.length()") { value(1) }
        }
        mvc.get("/v1/backoffice/reconciliation/items") { header("Authorization", "Bearer $admin") }.andExpect {
            jsonPath("$.length()") { value(1) }
        }
    }

    @Autowired lateinit var simulator: cd.capitalinvest.platform.payments.mobilemoney.SimulatorConnector

    private fun simulateLater(id: String) {
        val t = topupService.get(java.util.UUID.fromString(id))
        simulator.setOutcome(t.id, cd.capitalinvest.platform.payments.mobilemoney.CheckResult.Completed(t.amountMinor, "USD", "AM-77"))
    }

    @Test
    fun `une notification ne fait que declencher la relecture du statut`() {
        val alice = register("+243970000205")
        val id = startTopup(alice, 900, "mm-5")
        // La notification arrive avant que l'opérateur ait terminé : rien n'est crédité.
        mvc.post("/v1/webhooks/mobile-money/simulator") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topupId":"$id","status":"COMPLETED"}"""
        }.andExpect { status { isOk() } }
        assertEquals("PENDING", topupService.get(java.util.UUID.fromString(id)).status.name)
        assertEquals(0, balance(alice))

        simulateLater(id)
        mvc.post("/v1/webhooks/mobile-money/simulator") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topupId":"$id"}"""
        }.andExpect { status { isOk() } }
        assertEquals(900, balance(alice))

        val outcomes = jdbc.sql("SELECT outcome FROM mobile_money_event ORDER BY received_at").query(String::class.java).list()
        assertEquals(listOf("REFRESHED PENDING", "REFRESHED CONFIRMED"), outcomes)
        mvc.post("/v1/webhooks/mobile-money/inconnu") { content = "{}" }.andExpect { status { isNotFound() } }
    }
}
