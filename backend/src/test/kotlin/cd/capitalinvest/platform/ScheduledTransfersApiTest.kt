package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import cd.capitalinvest.platform.payments.ScheduledTransferService
import cd.capitalinvest.platform.payments.TransferFrequency
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduledTransfersApiTest : ApiTest() {
    @Autowired lateinit var scheduled: ScheduledTransferService
    @Autowired lateinit var agents: AgentService

    private fun schedule(s: Session, body: String) = mvc.post("/v1/me/scheduled-transfers") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun list(s: Session) = mvc.get("/v1/me/scheduled-transfers") { header("Authorization", "Bearer ${s.token}") }.body()

    /** Rend l'échéance due tout de suite, sans faire avancer l'horloge des sessions. */
    private fun makeDue(id: String, daysAgo: Int = 0) =
        jdbc.sql("UPDATE scheduled_transfer SET next_run_at = now() - make_interval(days => :d, mins => 1) WHERE id = :id::uuid")
            .param("d", daysAgo).param("id", id).update()

    private fun inbox(s: Session) = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${s.token}") }.body()["items"]

    @Test
    fun `echeances a 8 h heure de Lubumbashi, le 31 ramene au dernier jour du mois`() {
        fun at(s: String) = OffsetDateTime.parse(s)
        // Jeudi 19 novembre 2026, 07:00 à Lubumbashi (05:00 UTC) : jeudi, c'est encore aujourd'hui à 8 h.
        assertEquals(at("2026-11-19T06:00Z"), scheduled.nextRun(TransferFrequency.WEEKLY, 4, at("2026-11-19T05:00Z")))
        // Passé 8 h : la semaine suivante.
        assertEquals(at("2026-11-26T06:00Z"), scheduled.nextRun(TransferFrequency.WEEKLY, 4, at("2026-11-19T06:00Z")))
        assertEquals(at("2026-11-23T06:00Z"), scheduled.nextRun(TransferFrequency.WEEKLY, 1, at("2026-11-19T10:00Z")))
        assertEquals(at("2026-11-30T06:00Z"), scheduled.nextRun(TransferFrequency.MONTHLY, 31, at("2026-11-19T10:00Z")))
        assertEquals(at("2027-02-28T06:00Z"), scheduled.nextRun(TransferFrequency.MONTHLY, 31, at("2027-01-31T06:00Z")))
        assertEquals(at("2027-03-31T06:00Z"), scheduled.nextRun(TransferFrequency.MONTHLY, 31, at("2027-02-28T06:00Z")))
        assertEquals(at("2026-12-05T06:00Z"), scheduled.nextRun(TransferFrequency.MONTHLY, 5, at("2026-11-19T10:00Z")))
    }

    @Test
    fun `le loyer part chaque mois, une echeance ne part qu une fois, solde insuffisant prevenu puis pause`() {
        val alice = register("+243970001501")
        val bob = register("+243970001502")
        topupConfirmed(alice, 10_000, "t-1")

        schedule(alice, """{"toPhone":"0970001502","amountMinor":3000,"frequency":"MONTHLY","day":5,"note":"Loyer","pin":"1111"}""")
            .andExpect { status { isUnauthorized() } }
        val s = schedule(alice, """{"toPhone":"0970001502","amountMinor":3000,"frequency":"MONTHLY","day":5,"note":"Loyer","pin":"2580"}""")
            .andExpect { status { isCreated() } }.body()
        assertEquals("ACTIVE", s["status"].asText())
        assertEquals("Client +243970001502", s["recipientName"].asText())
        assertTrue(s["recipientPhone"].asText() != "+243970001502")
        assertEquals(5, OffsetDateTime.parse(s["nextRunAt"].asText()).plusHours(2).dayOfMonth)
        val id = s["id"].asText()

        assertEquals(0, scheduled.runDue()) // pas encore l'heure
        makeDue(id)
        assertEquals(1, scheduled.runDue())
        assertEquals(10_000 - 3_000 - 30, balance(alice)) // frais d'envoi d'1 %
        assertEquals(3_000, balance(bob))
        // Rejouée (deux serveurs, ou la même échéance remise due) : l'envoi ne repart pas.
        makeDue(id)
        scheduled.runDue()
        assertEquals(3_000, balance(bob))
        assertEquals("SENT", list(alice)[0]["lastResult"].asText())

        // Solde insuffisant trois fois de suite : le client est prévenu à chaque fois, puis l'envoi se met en pause.
        jdbc.sql("UPDATE scheduled_transfer SET amount_minor = 9000 WHERE id = :id::uuid").param("id", id).update()
        for (daysAgo in listOf(3, 2, 1)) {
            makeDue(id, daysAgo)
            scheduled.runDue()
        }
        val after = list(alice)[0]
        assertEquals("PAUSED", after["status"].asText())
        assertEquals("Solde insuffisant", after["lastFailure"].asText())
        assertTrue(after.path("nextRunAt").let { it.isMissingNode || it.isNull })
        val failures = inbox(alice).filter { it["kind"].asText() == "SCHEDULED_TRANSFER_FAILED" }
        assertEquals(3, failures.size)
        assertTrue(failures[0]["body"].asText().contains("mis en pause"))
        assertEquals(3_000, balance(bob))

        mvc.post("/v1/me/scheduled-transfers/$id/resume") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }.body().let { assertEquals("ACTIVE", it["status"].asText()) }
        mvc.delete("/v1/me/scheduled-transfers/$id") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isNoContent() } }
        assertEquals(0, list(alice).size())
        makeDue(id)
        assertEquals(0, scheduled.runDue())
    }

    @Test
    fun `refus a la creation et vue du back-office`() {
        val carl = register("+243970001503")
        register("+243970001504")
        schedule(carl, """{"toPhone":"0990000000","amountMinor":100,"frequency":"WEEKLY","day":1,"pin":"2580"}""")
            .andExpect { status { isNotFound() } }
        schedule(carl, """{"toPhone":"0970001503","amountMinor":100,"frequency":"WEEKLY","day":1,"pin":"2580"}""")
            .andExpect { status { isUnprocessableEntity() } }.body().let { assertEquals("SAME_ACCOUNT", it["code"].asText()) }
        schedule(carl, """{"toPhone":"0970001504","amountMinor":100,"frequency":"WEEKLY","day":9,"pin":"2580"}""")
            .andExpect { status { isUnprocessableEntity() } }.body().let { assertEquals("INVALID_DAY", it["code"].asText()) }
        schedule(carl, """{"toPhone":"0970001504","amountMinor":100,"frequency":"WEEKLY","day":1,"pin":"2580"}""").andExpect { status { isCreated() } }
        mvc.post("/v1/me/scheduled-transfers/${list(carl)[0]["id"].asText()}/pause") { header("Authorization", "Bearer ${carl.token}") }
            .andExpect { status { isOk() } }

        runCatching { agents.create(null, "conformite@capitalinvest.cd", "Agent conformité", "mot-de-passe-solide", AgentRole.COMPLIANCE) }
        val token = mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"conformite@capitalinvest.cd","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
        val bo = mvc.get("/v1/backoffice/customers/${carl.customerId}/scheduled-transfers") { header("Authorization", "Bearer $token") }.body()
        assertEquals("+243970001504", bo[0]["recipientPhone"].asText())
        assertEquals("PAUSED", bo[0]["status"].asText())
    }
}
