package cd.capitalinvest.platform

import cd.capitalinvest.platform.payments.PendingTransferService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingTransfersApiTest : ApiTest() {
    @Autowired lateinit var pending: PendingTransferService

    private fun send(s: Session, phone: String, amountMinor: Long, key: String, name: String = "Maman Chantal", pin: String = "2580") =
        mvc.post("/v1/transfers/pending") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"toPhone":"$phone","recipientName":"$name","amountMinor":$amountMinor,"pin":"$pin","note":"Pour le marché"}"""
        }

    private fun list(s: Session) = mvc.get("/v1/transfers/pending") { header("Authorization", "Bearer ${s.token}") }
        .andExpect { status { isOk() } }.body()

    private fun history(s: Session) = mvc.get("/v1/me/history") { header("Authorization", "Bearer ${s.token}") }
        .andExpect { status { isOk() } }.body()["items"]

    private fun sms(phone: String) = mvc.get("/v1/simulators/sms/$phone").body()["text"].asText()

    private fun fee(amountMinor: Long) = mvc.get("/v1/transfers/fee?amountMinor=$amountMinor") { header("Authorization", "Bearer ${alice.token}") }
        .body()["feeMinor"].asLong()

    private lateinit var alice: Session

    @Test
    fun `argent mis en attente, SMS au proche, verse a l'inscription`() {
        alice = register("+243970003201")
        val bob = register("+243970003209")
        topupConfirmed(alice, 20_000, "t1")
        val fee = fee(2_000)

        val sent = send(alice, "0970003202", 2_000, "k1").andExpect { status { isCreated() } }.body()
        assertEquals("PENDING", sent["status"].asText())
        assertEquals("+243970003202", sent["phone"].asText())
        assertEquals(fee, sent["feeMinor"].asLong())
        assertEquals(20_000 - 2_000 - fee, balance(alice))
        // Double appui : le même envoi, rien de plus débité.
        send(alice, "0970003202", 2_000, "k1").andExpect { status { isOk() } }
            .body().let { assertEquals(sent["id"], it["id"]) }
        assertEquals(20_000 - 2_000 - fee, balance(alice))

        assertTrue(sms("+243970003202").contains("vous a envoye 20,00 $"), sms("+243970003202"))
        history(alice)[0].let {
            assertEquals("TRANSFER_SENT", it["kind"].asText())
            assertEquals("PENDING", it["status"].asText())
            assertEquals("Maman Chantal", it["counterpartyName"].asText())
            assertEquals(2_000, it["amountMinor"].asLong())
            assertEquals("Pour le marché", it["note"].asText())
        }

        send(alice, "0970003201", 1_000, "k2").andExpect { jsonPath("$.code") { value("SAME_ACCOUNT") } }
        send(alice, bob.phone, 1_000, "k3").andExpect { jsonPath("$.code") { value("RECIPIENT_HAS_ACCOUNT") } }
        send(alice, "0970003203", 10_001, "k4").andExpect { jsonPath("$.code") { value("PENDING_TRANSFER_MAX") } }
        send(alice, "0970003203", 1_000, "k5", name = " ").andExpect { status { isUnprocessableEntity() } }
        send(alice, "0970003203", 1_000, "k6", pin = "1111").andExpect { status { isUnauthorized() } }

        // Le proche ouvre son compte avec ce numéro : l'argent est là, avec le nom de l'expéditeur.
        val mama = register("+243970003202")
        assertEquals(2_000, balance(mama))
        history(mama)[0].let {
            assertEquals("TRANSFER_RECEIVED", it["kind"].asText())
            assertEquals("Client +243970003201", it["counterpartyName"].asText())
            assertEquals("Pour le marché", it["note"].asText())
        }
        assertEquals("CLAIMED", list(alice)[0]["status"].asText())
        assertEquals("COMPLETED", history(alice).first { it["kind"].asText() == "TRANSFER_SENT" }["status"].asText())
        assertEquals(
            1,
            jdbc.sql("SELECT count(*) FROM notification WHERE customer_id = :c::uuid AND kind = 'TRANSFER_CLAIMED'")
                .param("c", alice.customerId).query(Int::class.java).single(),
        )
    }

    @Test
    fun `annulation et expiration rendent l'argent frais compris`() {
        alice = register("+243970003301")
        topupConfirmed(alice, 20_000, "t1")

        val a = send(alice, "0970003302", 1_000, "a").andExpect { status { isCreated() } }.body()["id"].asText()
        send(alice, "0970003303", 500, "b").andExpect { status { isCreated() } }
        send(alice, "0970003304", 500, "c").andExpect { status { isCreated() } }
        send(alice, "0970003305", 500, "d").andExpect { jsonPath("$.code") { value("PENDING_TRANSFERS_LIMIT") } }

        // Le compte ne peut pas être fermé tant qu'un envoi attend.
        mvc.get("/v1/me/closure") { header("Authorization", "Bearer ${alice.token}") }.body()["blockers"]
            .let { b -> assertTrue(b.any { it["code"].asText() == "PENDING_TRANSFER_OPEN" }) }

        mvc.delete("/v1/transfers/pending/$a") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }.andExpect { jsonPath("$.status") { value("CANCELLED") } }
        mvc.delete("/v1/transfers/pending/$a") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { jsonPath("$.code") { value("PENDING_TRANSFER_SETTLED") } }
        assertEquals(20_000 - 500 - fee(500) - 500 - fee(500), balance(alice))
        history(alice)[0].let {
            assertEquals("TRANSFER_REFUND", it["kind"].asText())
            assertEquals(1_000, it["amountMinor"].asLong())
            assertEquals(fee(1_000), it["feeMinor"].asLong())
            assertEquals(1_000 + fee(1_000), it["netMinor"].asLong())
        }

        // Dépenses du mois : l'envoi annulé ne compte plus, ni ses frais.
        mvc.get("/v1/me/spending") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isOk() } }.body()["categories"]
            .associate { it["category"].asText() to it["amountMinor"].asLong() }
            .let { assertEquals(mapOf("TRANSFERS" to 1_000L, "FEES" to 2 * fee(500)), it) }

        // Personne n'a ouvert de compte en 7 jours : remboursé.
        testClock.advance(Duration.ofDays(6))
        assertEquals(0, pending.expireDue())
        testClock.advance(Duration.ofDays(1).plusMinutes(1))
        assertEquals(2, pending.expireDue())
        alice = login(alice.phone) // la session a expiré entre-temps
        assertEquals(20_000, balance(alice))
        assertEquals(listOf("EXPIRED", "EXPIRED", "CANCELLED"), list(alice).map { it["status"].asText() }.sorted().reversed())
        // Trop tard : le proche qui s'inscrit maintenant ne reçoit rien.
        assertEquals(0, balance(register("+243970003303")))
        // Un autre client ne peut pas annuler l'envoi d'alice.
        val eve = register("+243970003399")
        mvc.delete("/v1/transfers/pending/$a") { header("Authorization", "Bearer ${eve.token}") }.andExpect { status { isNotFound() } }
    }
}
