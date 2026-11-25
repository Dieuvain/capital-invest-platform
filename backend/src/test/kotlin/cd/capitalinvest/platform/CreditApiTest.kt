package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import cd.capitalinvest.platform.credit.CreditMath
import cd.capitalinvest.platform.credit.CreditService
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Crédit express : offre, score, versement, échéances, retard, défaut, back-office. */
class CreditApiTest : ApiTest() {
    @Autowired lateinit var agents: AgentService
    @Autowired lateinit var credit: CreditService

    @AfterEach
    fun resetSettings() {
        jdbc.sql(
            """UPDATE credit_settings SET min_score = 40, review_threshold_minor = 30000, min_amount_minor = 1000, max_amount_minor = 100000,
               monthly_rate_bp = 250, durations_months = '{1,2,3,6}', ceiling_percent_of_inflow = 50, default_after_days = 30 WHERE id = 1""",
        ).update()
    }

    private fun today(): LocalDate = LocalDate.ofInstant(testClock.instant(), java.time.ZoneId.of("Africa/Lubumbashi"))

    private fun absent(n: JsonNode?) = n == null || n.isNull

    private fun bearer(s: Session) = "Bearer ${s.token}"

    private fun overview(s: Session): JsonNode = mvc.get("/v1/me/credit") { header("Authorization", bearer(s)) }.andExpect { status { isOk() } }.body()

    private fun quote(s: Session, amount: Long, months: Int) = mvc.post("/v1/me/credit/quote") {
        header("Authorization", bearer(s))
        contentType = MediaType.APPLICATION_JSON
        content = """{"amountMinor":$amount,"durationMonths":$months}"""
    }

    private fun apply(s: Session, amount: Long, months: Int, key: String = "k-${UUID.randomUUID()}", pin: String = "2580") = mvc.post("/v1/me/credit") {
        header("Authorization", bearer(s))
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"amountMinor":$amount,"durationMonths":$months,"pin":"$pin"}"""
    }

    private fun repay(s: Session, id: String, mode: String, key: String = "r-${UUID.randomUUID()}") = mvc.post("/v1/me/credit/$id/repayments") {
        header("Authorization", bearer(s))
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"mode":"$mode","pin":"2580"}"""
    }

    private fun notificationKinds(s: Session): List<String> =
        mvc.get("/v1/me/notifications") { header("Authorization", bearer(s)) }.body()["items"].map { it["kind"].asText() }

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent ${role.name.lowercase()}", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun bo(token: String, path: String, body: String) = mvc.post("/v1/backoffice$path") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun systemBalance(code: String): Long = jdbc.sql(
        """SELECT COALESCE(SUM(CASE WHEN e.direction = a.normal_side THEN e.amount_minor ELSE -e.amount_minor END), 0)
           FROM ledger_account a LEFT JOIN ledger_entry e ON e.account_id = a.id WHERE a.code = :c""",
    ).param("c", code).query(Long::class.java).single()

    /**
     * Client de niveau 2 avec trois mois d'historique : compte ouvert il y a 100 jours,
     * une recharge par tranche de 30 jours (l'heure des opérations est reculée dans la base).
     */
    private fun goodCustomer(phone: String, monthly: Long = 30_000): Session {
        val s = register(phone, fullName = "Jeanne Kabila Mwamba")
        setKycLevel(s, 2)
        for (k in 0..2) {
            topupConfirmed(s, monthly, "top-$phone-$k")
            jdbc.sql(
                """UPDATE ledger_transaction SET created_at = now() - make_interval(days => :d)
                   WHERE id = (SELECT t.id FROM ledger_transaction t JOIN ledger_entry e ON e.transaction_id = t.id
                               WHERE e.account_id = :w::uuid AND t.type = 'TOPUP' ORDER BY t.created_at DESC LIMIT 1)""",
            ).param("d", 30 * (2 - k) + 1).param("w", s.walletId).update()
        }
        jdbc.sql("UPDATE customer SET created_at = now() - interval '100 days' WHERE id = :id::uuid").param("id", s.customerId).update()
        return s
    }

    /** Avance l'horloge, lance les prélèvements et se reconnecte (la session a expiré). */
    private fun daysLater(s: Session, days: Long): Session {
        testClock.advance(Duration.ofDays(days))
        credit.runDue()
        return login(s.phone)
    }

    /** Vide le portefeuille vers l'épargne, sans frais. */
    private fun emptyWallet(s: Session) {
        val b = balance(s)
        if (b > 0) mvc.post("/v1/me/savings/deposits") {
            header("Authorization", bearer(s))
            header("Idempotency-Key", "save-${UUID.randomUUID()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amountMinor":$b}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `pas d'offre - niveau 1, puis score trop bas avec ses raisons et leviers`() {
        val s = register("+243970006001")
        val o1 = overview(s)
        assertTrue(absent(o1["offer"]))
        assertEquals("KYC_LEVEL_REQUIRED", o1["refusal"]["code"].asText())
        apply(s, 5_000, 3).andExpect { status { isForbidden() }; jsonPath("$.code") { value("KYC_LEVEL_REQUIRED") } }

        setKycLevel(s, 2)
        val o2 = overview(s)["refusal"]
        assertEquals("SCORE_TOO_LOW", o2["code"].asText())
        assertEquals(0, o2["score"].asInt())
        assertTrue(o2["factors"].any { it["label"].asText().contains("Aucune recharge") && !it["positive"].asBoolean() })
        assertTrue(o2["levers"].any { it.asText().contains("paie") })
        quote(s, 5_000, 3).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("SCORE_TOO_LOW") } }
    }

    @Test
    fun `offre - score, plafond, simulation avec TEG et echeancier`() {
        val s = goodCustomer("+243970006002")
        val offer = overview(s)["offer"]
        // 100 jours (15) + 300 $ par mois (25) + 3 mois sur 3 (20).
        assertEquals(60, offer["score"].asInt())
        assertEquals(15_000, offer["ceilingMinor"].asLong()) // 50 % de 300 $
        assertEquals(listOf(1, 2, 3, 6), offer["durationsMonths"].map { it.asInt() })
        assertEquals(3449, offer["aprBp"].asInt()) // 2,5 % par mois
        assertTrue(offer["factors"].any { it["label"].asText() == "300,00 $ reçus par mois en moyenne sur 3 mois" })

        val q = quote(s, 10_000, 3).andExpect { status { isOk() } }.body()
        assertEquals(3_502, q["instalmentMinor"].asLong())
        assertEquals(504, q["totalInterestMinor"].asLong())
        assertEquals(10_504, q["totalDueMinor"].asLong())
        assertFalse(q["needsReview"].asBoolean())
        assertEquals(10_000, q["schedule"].sumOf { it["principalMinor"].asLong() })
        assertEquals(today().plusMonths(1).toString(), q["schedule"][0]["dueDate"].asText())

        quote(s, 15_100, 3).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_CREDIT_AMOUNT") } }
        quote(s, 500, 3).andExpect { jsonPath("$.code") { value("INVALID_CREDIT_AMOUNT") } }
        quote(s, 10_000, 4).andExpect { jsonPath("$.code") { value("INVALID_DURATION") } }
    }

    @Test
    fun `versement - PIN, argent sur le compte, historique, SMS, une seule fois`() {
        val s = goodCustomer("+243970006003")
        val before = balance(s)
        apply(s, 10_000, 3, pin = "1111").andExpect { status { isUnauthorized() } }

        val c = apply(s, 10_000, 3, key = "demande-1").andExpect { status { isCreated() } }.body()
        assertEquals("ACTIVE", c["status"].asText())
        assertEquals(3, c["instalments"].size())
        assertEquals(10_000, c["outstandingPrincipalMinor"].asLong())
        assertEquals(3_502, c["nextInstalment"]["amountMinor"].asLong())
        assertEquals(before + 10_000, balance(s))
        assertEquals(10_000, systemBalance("LOANS_RECEIVABLE_USD"))

        // Rejouer la même demande ne verse pas deux fois.
        assertEquals(c["id"].asText(), apply(s, 10_000, 3, key = "demande-1").body()["id"].asText())
        assertEquals(before + 10_000, balance(s))
        apply(s, 5_000, 3).andExpect { status { isConflict() }; jsonPath("$.code") { value("CREDIT_ALREADY_OPEN") } }

        val item = mvc.get("/v1/me/history") { header("Authorization", bearer(s)) }.body()["items"][0]
        assertEquals("CREDIT_DISBURSEMENT", item["kind"].asText())
        assertEquals(10_000, item["amountMinor"].asLong())
        assertTrue("CREDIT_DISBURSED" in notificationKinds(s))
        val o = overview(s)
        assertTrue(absent(o["offer"]))
        assertEquals(c["id"].asText(), o["current"]["id"].asText())

        // Le compte ne se ferme pas avec un crédit en cours.
        val blockers = mvc.get("/v1/me/closure") { header("Authorization", bearer(s)) }.body()["blockers"].map { it["code"].asText() }
        assertTrue("CREDIT_OPEN" in blockers)
    }

    @Test
    fun `echeances - rappel avant, prelevement le jour dit, interets en revenus`() {
        var s = goodCustomer("+243970006004")
        val id = apply(s, 10_000, 3).body()["id"].asText()
        val firstDue = today().plusMonths(1)

        s = daysLater(s, java.time.temporal.ChronoUnit.DAYS.between(today(), firstDue) - 2)
        assertTrue("CREDIT_REMINDER" in notificationKinds(s))
        assertEquals(0, systemBalance("INTEREST_REVENUE_USD"))

        val before = balance(s)
        s = daysLater(s, 2)
        val c = mvc.get("/v1/me/credit/$id") { header("Authorization", bearer(s)) }.body()
        assertEquals("ACTIVE", c["status"].asText())
        assertEquals("PAID", c["instalments"][0]["status"].asText())
        assertEquals(before - 3_502, balance(s))
        assertEquals(250, systemBalance("INTEREST_REVENUE_USD"))
        assertEquals(10_000 - 3_252, systemBalance("LOANS_RECEIVABLE_USD"))
        assertEquals("CREDIT_REPAYMENT", mvc.get("/v1/me/history") { header("Authorization", bearer(s)) }.body()["items"][0]["kind"].asText())
        // Le même jour, rien n'est prélevé deux fois.
        credit.runDue()
        assertEquals(before - 3_502, balance(s))
    }

    @Test
    fun `retard puis defaut, prelevement partiel, reechelonnement par l'agent de credit`() {
        var s = goodCustomer("+243970006005")
        val id = apply(s, 10_000, 3).body()["id"].asText()
        emptyWallet(s)

        s = daysLater(s, 31)
        var c = mvc.get("/v1/me/credit/$id") { header("Authorization", bearer(s)) }.body()
        assertEquals("LATE", c["status"].asText())
        assertEquals(3_502, c["dueNowMinor"].asLong())
        assertTrue("CREDIT_LATE" in notificationKinds(s))
        repay(s, id, "DUE").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INSUFFICIENT_FUNDS") } }

        // Une recharge de 20 $ : le prélèvement suivant prend ce qu'il y a.
        topupConfirmed(s, 2_000, "petite-recharge")
        credit.runDue()
        c = mvc.get("/v1/me/credit/$id") { header("Authorization", bearer(s)) }.body()
        assertEquals(0, balance(s))
        assertEquals(1_502, c["dueNowMinor"].asLong())
        assertEquals("LATE", c["status"].asText())

        s = daysLater(s, 30)
        c = mvc.get("/v1/me/credit/$id") { header("Authorization", bearer(s)) }.body()
        assertEquals("DEFAULTED", c["status"].asText())
        assertTrue("CREDIT_DEFAULTED" in notificationKinds(s))

        val officer = agent(AgentRole.CREDIT_OFFICER)
        val kyc = agent(AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/credits?status=DEFAULTED") { header("Authorization", "Bearer $kyc") }.andExpect { status { isForbidden() } }
        val queue = mvc.get("/v1/backoffice/credits?status=DEFAULTED") { header("Authorization", "Bearer $officer") }.body()
        assertEquals(id, queue[0]["credit"]["id"].asText())
        assertEquals("Jeanne Kabila Mwamba", queue[0]["customerName"].asText())

        bo(officer, "/credits/$id/reschedule", """{"durationMonths":6}""").andExpect { jsonPath("$.code") { value("NOTE_REQUIRED") } }
        bo(officer, "/credits/$id/write-off", """{"note":"Comité"}""").andExpect { status { isForbidden() } }
        val r = bo(officer, "/credits/$id/reschedule", """{"durationMonths":6,"note":"Accord avec la cliente après sa visite"}""")
            .andExpect { status { isOk() } }.body()["credit"]
        assertEquals("ACTIVE", r["status"].asText())
        assertEquals(1, r["rescheduleCount"].asInt())
        assertEquals(0, r["dueNowMinor"].asLong())
        // Le capital restant est étalé à nouveau ; rien n'est perdu.
        val open = r["instalments"].filter { it["status"].asText() == "UPCOMING" }
        assertEquals(6, open.size)
        assertEquals(10_000 - 1_750, open.sumOf { it["principalMinor"].asLong() })
        assertTrue("CREDIT_RESCHEDULED" in notificationKinds(s))
    }

    @Test
    fun `defaut puis passage en perte par l'administrateur`() {
        var s = goodCustomer("+243970006006")
        val id = apply(s, 10_000, 1).body()["id"].asText()
        emptyWallet(s)
        s = daysLater(s, 31)
        s = daysLater(s, 30)
        val admin = agent(AgentRole.ADMIN)
        val r = bo(admin, "/credits/$id/write-off", """{"note":"Décision du comité du 25/11"}""").andExpect { status { isOk() } }.body()["credit"]
        assertEquals("WRITTEN_OFF", r["status"].asText())
        assertEquals(0, systemBalance("LOANS_RECEIVABLE_USD"))
        assertEquals(10_000, systemBalance("LOAN_LOSSES_USD"))
        assertEquals("CREDIT_WRITTEN_OFF", overview(s)["refusal"]["code"].asText())
    }

    @Test
    fun `revue - au-dessus du seuil l'agent decide, reglages reserves a l'administrateur`() {
        val officer = agent(AgentRole.CREDIT_OFFICER)
        val admin = agent(AgentRole.ADMIN)
        val settings = mvc.get("/v1/backoffice/credit-settings") { header("Authorization", "Bearer $officer") }.body()
        assertEquals(30_000, settings["reviewThresholdMinor"].asLong())
        val changed = json.writeValueAsString(
            json.convertValue(settings, Map::class.java).toMutableMap().apply { put("reviewThresholdMinor", 5_000) },
        )
        mvc.put("/v1/backoffice/credit-settings") {
            header("Authorization", "Bearer $officer"); contentType = MediaType.APPLICATION_JSON; content = changed
        }.andExpect { status { isForbidden() } }
        mvc.put("/v1/backoffice/credit-settings") {
            header("Authorization", "Bearer $admin"); contentType = MediaType.APPLICATION_JSON; content = changed
        }.andExpect { status { isOk() }; jsonPath("$.reviewThresholdMinor") { value(5_000) } }

        val s = goodCustomer("+243970006007")
        val before = balance(s)
        assertTrue(quote(s, 10_000, 3).body()["needsReview"].asBoolean())
        val pending = apply(s, 10_000, 3).andExpect { status { isCreated() } }.body()
        assertEquals("PENDING_REVIEW", pending["status"].asText())
        assertEquals(before, balance(s))
        val id = pending["id"].asText()

        val queue = mvc.get("/v1/backoffice/credits?status=PENDING_REVIEW") { header("Authorization", "Bearer $officer") }.body()
        assertEquals(60, queue[0]["credit"]["score"].asInt())
        assertTrue(queue[0]["factors"].size() >= 3)
        val approved = bo(officer, "/credits/$id/approve", """{"note":"Paie régulière de la mine"}""").andExpect { status { isOk() } }.body()
        assertEquals("ACTIVE", approved["credit"]["status"].asText())
        assertEquals("Agent credit_officer", approved["decidedBy"].asText())
        assertEquals(before + 10_000, balance(s))
        bo(officer, "/credits/$id/approve", "{}").andExpect { status { isConflict() } }

        // Une autre demande, refusée avec une explication ; une troisième, retirée par le client.
        val s2 = goodCustomer("+243970006008")
        val id2 = apply(s2, 10_000, 3).body()["id"].asText()
        bo(officer, "/credits/$id2/reject", "{}").andExpect { jsonPath("$.code") { value("NOTE_REQUIRED") } }
        bo(officer, "/credits/$id2/reject", """{"note":"Revenus à confirmer : passez à l'agence avec vos fiches de paie."}""")
            .andExpect { status { isOk() } }
        assertTrue("CREDIT_REJECTED" in notificationKinds(s2))
        val o = overview(s2)
        assertNotNull(o["offer"])
        assertEquals("REJECTED", o["past"][0]["status"].asText())
        val id3 = apply(s2, 10_000, 2).body()["id"].asText()
        mvc.post("/v1/me/credit/$id3/cancel") { header("Authorization", bearer(s2)) }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("REJECTED") } }
    }

    @Test
    fun `remboursement anticipe - sans les interets a venir, puis plafond plus haut`() {
        var s = goodCustomer("+243970006009")
        val id = apply(s, 10_000, 3).body()["id"].asText()
        val before = balance(s)
        val view = mvc.get("/v1/me/credit/$id") { header("Authorization", bearer(s)) }.body()
        assertEquals(10_000, view["payoffMinor"].asLong())

        // Payer l'échéance en avance, puis tout le reste.
        val afterFirst = repay(s, id, "DUE", key = "avance").andExpect { status { isOk() } }.body()
        assertEquals("PAID", afterFirst["instalments"][0]["status"].asText())
        assertEquals(before - 3_502, balance(s))
        repay(s, id, "DUE", key = "avance")
        assertEquals(before - 3_502, balance(s)) // même clé : pas de second prélèvement
        val payoff = afterFirst["payoffMinor"].asLong()
        assertEquals(10_000 - 3_252, payoff)

        val done = repay(s, id, "FULL").andExpect { status { isOk() } }.body()
        assertEquals("REPAID", done["status"].asText())
        assertEquals(before - 3_502 - payoff, balance(s))
        assertEquals(0, systemBalance("LOANS_RECEIVABLE_USD"))
        assertEquals(250, systemBalance("INTEREST_REVENUE_USD"))
        assertTrue("CREDIT_REPAID" in notificationKinds(s))
        repay(s, id, "DUE").andExpect { jsonPath("$.code") { value("CREDIT_NOT_RUNNING") } }

        val offer = overview(s)["offer"]
        assertTrue(offer["factors"].any { it["label"].asText() == "Un crédit remboursé à l'heure" })
        assertEquals(70, offer["score"].asInt())
        assertEquals(18_000, offer["ceilingMinor"].asLong()) // + 25 %, arrondi à 10 $
    }

    @Test
    fun `calcul - echeancier sans taux, derniere echeance qui solde le capital`() {
        val plan = CreditMath.schedule(10_000, 3, 0, LocalDate.of(2026, 1, 31))
        assertEquals(listOf(3_334L, 3_334L, 3_332L), plan.map { it.amountMinor })
        assertEquals(LocalDate.of(2026, 2, 28), plan[0].dueDate)
        val p6 = CreditMath.schedule(100_000, 6, 250, LocalDate.of(2026, 11, 25))
        assertEquals(100_000, p6.sumOf { it.principalMinor })
        assertTrue(p6.last().amountMinor <= p6.first().amountMinor)
    }
}
