package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import cd.capitalinvest.platform.payments.WithdrawalService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

/** Retraits vers mobile money, par le simulateur. Frais : 1,5 %, au moins 0,25 $. */
class WithdrawalsApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var withdrawals: WithdrawalService

    private fun withdraw(s: Session, amount: Long, key: String, pin: String = "2580", operator: String = "MPESA") =
        mvc.post("/v1/withdrawals") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"$operator","amountMinor":$amount,"pin":"$pin"}"""
        }

    private fun payout(id: String, success: Boolean, failureCode: String? = null) =
        mvc.post("/v1/simulators/mobile-money/payouts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"withdrawalId":"$id","success":$success${failureCode?.let { ""","failureCode":"$it"""" } ?: ""}}"""
        }.andExpect { status { isOk() } }.body()

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent $email", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun account(code: String): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(CASE WHEN e.direction = a.normal_side THEN e.amount_minor ELSE -e.amount_minor END), 0)
               FROM ledger_account a LEFT JOIN ledger_entry e ON e.account_id = a.id WHERE a.code = :c""",
        ).param("c", code).query(Long::class.java).single()

    @Test
    fun `retrait verse - l'argent est bloque des la demande puis part sur le compte de reglement`() {
        val alice = register("+243970000401")
        topupConfirmed(alice, 10000, "w-top-1")
        mvc.get("/v1/withdrawals/fee?amountMinor=4000") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            jsonPath("$.feeMinor") { value(60) }
            jsonPath("$.totalMinor") { value(4060) }
        }

        val w = withdraw(alice, 4000, "w-1").andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.feeMinor") { value(60) }
            jsonPath("$.msisdn") { value("243970000401") }
        }.body()
        assertEquals(5940, balance(alice))
        assertEquals(4000, account("PAYOUTS_IN_TRANSIT_USD"))
        // Rejouer la même clé ne bloque pas une deuxième fois.
        withdraw(alice, 4000, "w-1").andExpect { jsonPath("$.id") { value(w["id"].asText()) } }
        assertEquals(5940, balance(alice))

        assertEquals("CONFIRMED", payout(w["id"].asText(), success = true)["status"].asText())
        assertEquals(5940, balance(alice))
        assertEquals(0, account("PAYOUTS_IN_TRANSIT_USD"))
        assertEquals(60, account("FEES_REVENUE_USD"))

        val bob = register("+243970000402")
        mvc.get("/v1/withdrawals/${w["id"].asText()}") { header("Authorization", "Bearer ${bob.token}") }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `un refus de l'operateur rend montant et frais, avec le motif`() {
        val alice = register("+243970000403")
        topupConfirmed(alice, 10000, "w-top-2")
        val id = withdraw(alice, 2000, "w-2").body()["id"].asText()
        val failed = payout(id, success = false, failureCode = "RECIPIENT_NOT_FOUND")
        assertEquals("FAILED", failed["status"].asText())
        assertEquals("RECIPIENT_NOT_FOUND", failed["failureCode"].asText())
        assertEquals(10000, balance(alice))
        assertEquals(0, account("FEES_REVENUE_USD"))
    }

    @Test
    fun `mauvais PIN ou solde insuffisant - rien n'est bloque`() {
        val alice = register("+243970000404")
        topupConfirmed(alice, 1000, "w-top-3")
        withdraw(alice, 500, "w-3", pin = "9999").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
        }
        withdraw(alice, 990, "w-4").andExpect { jsonPath("$.code") { value("INSUFFICIENT_FUNDS") } }
        assertEquals(1000, balance(alice))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM withdrawal").query(Int::class.java).single())
    }

    @Test
    fun `les retraits comptent dans le plafond journalier d'envoi`() {
        val alice = register("+243970000405")
        repeat(4) { topupConfirmed(alice, 20000, "w-top-5-$it") } // 200 $ au plus par opération au niveau 1
        repeat(3) { withdraw(alice, 20000, "w-5-$it").andExpect { status { isAccepted() } } }
        withdraw(alice, 100, "w-5-x").andExpect { jsonPath("$.code") { value("LIMIT_DAILY") } }
        // Un retrait remboursé libère le plafond.
        val first = jdbc.sql("SELECT id FROM withdrawal ORDER BY created_at LIMIT 1").query(UUID::class.java).single()
        payout(first.toString(), success = false)
        withdraw(alice, 100, "w-5-y").andExpect { status { isAccepted() } }
    }

    @Test
    fun `sans reponse apres 24 h le retrait est a verifier - jamais rembourse sur un doute`() {
        val alice = register("+243970000406")
        topupConfirmed(alice, 10000, "w-top-6")
        val id = withdraw(alice, 3000, "w-6").body()["id"].asText()
        testClock.advance(Duration.ofHours(25))
        withdrawals.checkDue()
        assertEquals("REVIEW", withdrawals.get(UUID.fromString(id)).status.name)

        val compliance = agent(AgentRole.COMPLIANCE)
        mvc.get("/v1/backoffice/topups/review") { header("Authorization", "Bearer $compliance") }.andExpect {
            jsonPath("$[0].type") { value("WITHDRAWAL") }
            jsonPath("$[0].id") { value(id) }
        }
        // L'opérateur n'a toujours pas répondu : rembourser est refusé.
        mvc.post("/v1/backoffice/withdrawals/$id/resolve") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"REFUND","note":"Client au guichet"}"""
        }.andExpect { jsonPath("$.code") { value("PROVIDER_NOT_FAILED") } }

        payout(id, success = false) // l'opérateur confirme enfin l'échec
        mvc.post("/v1/backoffice/withdrawals/$id/resolve") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"REFUND","note":"Échec confirmé par M-Pesa"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("FAILED") }
        }
        assertEquals(10000, balance(login(alice.phone))) // la session de la veille a expiré
    }

    @Test
    fun `le rapprochement trouve un retrait note verse que l'operateur n'a pas paye, et l'agent rembourse`() {
        val alice = register("+243970000407")
        topupConfirmed(alice, 10000, "w-top-7")
        val id = withdraw(alice, 2500, "w-7").body()["id"].asText()
        payout(id, success = true)
        assertEquals(10000 - 2500 - 38, balance(alice))
        payout(id, success = false) // plus tard, l'opérateur annonce que le versement a échoué

        val admin = agent(AgentRole.ADMIN)
        val today = LocalDate.now(testClock.withZone(ZoneId.of("Africa/Lubumbashi")))
        mvc.post("/v1/backoffice/reconciliation/runs") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"businessDate":"$today"}"""
        }.andExpect { jsonPath("$.discrepancies") { value(1) } }
        val item = mvc.get("/v1/backoffice/reconciliation/items") { header("Authorization", "Bearer $admin") }.body()
            .single { it["type"].asText() == "WITHDRAWAL" }
        assertEquals("MARKED_PAID_NOT_PAID", item["kind"].asText())
        assertEquals(id, item["targetId"].asText())

        mvc.post("/v1/backoffice/reconciliation/items/${item["id"].asText()}/resolve") {
            header("Authorization", "Bearer $admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"action":"CREDIT","note":"Échec confirmé par M-Pesa"}"""
        }.andExpect { status { isOk() } }
        assertEquals(10000, balance(alice))
        assertEquals("FAILED", withdrawals.get(UUID.fromString(id)).status.name)
    }

    @Test
    fun `une notification de versement declenche la relecture`() {
        val alice = register("+243970000408")
        topupConfirmed(alice, 10000, "w-top-8")
        val id = withdraw(alice, 1000, "w-8").body()["id"].asText()
        mvc.post("/v1/webhooks/mobile-money/simulator") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"withdrawalId":"$id"}"""
        }.andExpect { status { isOk() } }
        val outcome = jdbc.sql("SELECT outcome FROM mobile_money_event WHERE withdrawal_id = :id::uuid").param("id", id)
            .query(String::class.java).single()
        assertEquals("REFRESHED PENDING", outcome)
    }
}
