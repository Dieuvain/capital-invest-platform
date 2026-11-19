package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComplaintsApiTest : ApiTest() {
    @Autowired lateinit var agents: AgentService

    private fun agent(role: AgentRole): String {
        val email = "${role.name.lowercase()}@capitalinvest.cd"
        runCatching { agents.create(null, email, "Agent ${role.name.lowercase()}", "mot-de-passe-solide", role) }
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"mot-de-passe-solide"}"""
        }.body()["accessToken"].asText()
    }

    private fun lastOperation(s: Session): String =
        mvc.get("/v1/me/history") { header("Authorization", "Bearer ${s.token}") }.body()["items"][0]["id"].asText()

    private fun complain(s: Session, body: String) = mvc.post("/v1/me/complaints") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun inbox(s: Session) = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${s.token}") }.body()["items"]

    @Test
    fun `le client reclame sur un envoi, la conformite repond, le client est prevenu`() {
        val alice = register("+243970001401")
        register("+243970001402")
        topupConfirmed(alice, 5_000, "t-1")
        transfer(alice, "+243970001402", 2_000, "s-1").andExpect { status { isCreated() } }
        val tx = lastOperation(alice)

        val c = complain(alice, """{"transactionId":"$tx","category":"NOT_RECEIVED","message":"Mon frère dit ne rien avoir reçu"}""")
            .andExpect { status { isCreated() } }.body()
        assertTrue(c["reference"].asText().matches(Regex("REC-\\d{8}-[0-9A-F]{6}")))
        assertEquals("OPEN", c["status"].asText())
        assertEquals("TRANSFER_SENT", c["operation"]["kind"].asText())
        assertEquals("Réclamation reçue", inbox(alice)[0]["title"].asText())

        // Une seule réclamation ouverte par opération.
        complain(alice, """{"transactionId":"$tx","category":"OTHER","message":"Encore"}""")
            .andExpect { status { isConflict() } }.body().let { assertEquals("COMPLAINT_ALREADY_OPEN", it["code"].asText()) }

        val compliance = agent(AgentRole.COMPLIANCE)
        val queue = mvc.get("/v1/backoffice/complaints") { header("Authorization", "Bearer $compliance") }.body()
        assertEquals(1, queue.size())
        assertEquals("Client +243970001401", queue[0]["customerName"].asText())
        assertEquals("+243970001402", queue[0]["complaint"]["operation"]["counterpartyPhone"].asText())
        assertFalse(queue[0]["overdue"].asBoolean())

        val id = c["id"].asText()
        mvc.post("/v1/backoffice/complaints/$id/answer") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"outcome":"UNFOUNDED","answer":"L'argent est bien arrivé sur le compte du +243970001402 le 19/11."}"""
        }.andExpect { status { isOk() } }.body().let {
            assertEquals("ANSWERED", it["complaint"]["status"].asText())
            assertEquals("Agent compliance", it["answeredByName"].asText())
        }
        mvc.post("/v1/backoffice/complaints/$id/answer") {
            header("Authorization", "Bearer $compliance")
            contentType = MediaType.APPLICATION_JSON
            content = """{"outcome":"FOUNDED","answer":"Deux fois"}"""
        }.andExpect { status { isConflict() } }

        val mine = mvc.get("/v1/me/complaints") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals("UNFOUNDED", mine[0]["outcome"].asText())
        assertFalse(mine[0]["operation"]["counterpartyPhone"].asText().contains("0001402")) // numéro masqué côté app
        assertEquals("Réponse à votre réclamation", inbox(alice)[0]["title"].asText())
        assertEquals(1, jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'COMPLAINT_ANSWERED_UNFOUNDED'").query(Int::class.java).single())

        // Réponse donnée : une nouvelle réclamation sur la même opération redevient possible.
        complain(alice, """{"transactionId":"$tx","category":"OTHER","message":"Je conteste la réponse"}""").andExpect { status { isCreated() } }
    }

    @Test
    fun `operation d un autre client, file triee, retard, droits`() {
        val bob = register("+243970001403")
        val eve = register("+243970001404")
        topupConfirmed(eve, 1_000, "t-1")
        complain(bob, """{"transactionId":"${lastOperation(eve)}","category":"NOT_ME","message":"?"}""")
            .andExpect { status { isNotFound() } }

        complain(bob, """{"category":"OTHER","message":"Je ne reçois pas les SMS"}""").andExpect { status { isCreated() } }
        testClock.advance(Duration.ofDays(8))
        val bob2 = login(bob.phone)
        complain(bob2, """{"category":"NOT_ME","message":"Quelqu'un a utilisé mon compte"}""").andExpect { status { isCreated() } }

        val admin = agent(AgentRole.ADMIN)
        val queue = mvc.get("/v1/backoffice/complaints") { header("Authorization", "Bearer $admin") }.body()
        assertEquals(listOf("NOT_ME", "OTHER"), queue.map { it["complaint"]["category"].asText() })
        assertTrue(queue[0]["urgent"].asBoolean())
        assertTrue(queue[1]["overdue"].asBoolean())

        val kyc = agent(AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/complaints") { header("Authorization", "Bearer $kyc") }.andExpect { status { isForbidden() } }

        repeat(3) { complain(bob2, """{"category":"OTHER","message":"n°$it"}""").andExpect { status { isCreated() } } }
        complain(bob2, """{"category":"OTHER","message":"de trop"}""")
            .andExpect { status { isUnprocessableEntity() } }.body().let { assertEquals("COMPLAINT_LIMIT", it["code"].asText()) }
    }
}
