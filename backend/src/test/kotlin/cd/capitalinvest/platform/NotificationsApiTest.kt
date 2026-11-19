package cd.capitalinvest.platform

import cd.capitalinvest.platform.auth.SimulatedSmsGateway
import cd.capitalinvest.platform.notifications.NotificationDispatcher
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chaque mouvement d'argent prévient le client : un SMS court et une ligne dans l'app. */
class NotificationsApiTest : ApiTest() {

    @Autowired lateinit var sms: SimulatedSmsGateway
    @Autowired lateinit var dispatcher: NotificationDispatcher

    private fun inbox(s: Session): JsonNode =
        mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()

    @BeforeEach
    fun clearSms() = sms.clear()

    /** Les SMS de notification, sans les codes de connexion. */
    private fun texts(s: Session) = sms.messages(s.phone).filter { !it.contains("votre code") }

    @Test
    fun `recharge, envoi et argent recu - SMS court sans accents, et boite de l app`() {
        val alice = register("+243970000601")
        val bob = register("+243970000602")
        topupConfirmed(alice, 10000, "n-top-1")
        mvc.post("/v1/transfers") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "n-tr-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"toPhone":"${bob.phone}","amountMinor":2500,"note":"Pour l'école","pin":"2580"}"""
        }.andExpect { status { isCreated() } }
        // Rejouer l'envoi ne renvoie pas de SMS.
        transfer(alice, bob.phone, 2500, "n-tr-1")

        val aliceSms = texts(alice)
        assertEquals(2, aliceSms.size, aliceSms.toString())
        assertTrue(aliceSms[0].startsWith("Capital Invest: recharge M-Pesa de 100,00 \$ recue. Solde: 100,00 \$. Ref CI-"), aliceSms[0])
        assertEquals(
            "Capital Invest: envoi de 25,00 \$ a Client +243970000602, frais 0,25 \$. Solde: 74,75 \$. Pas vous? Appelez-nous.",
            aliceSms[1],
        )
        aliceSms.forEach { assertTrue(it.length <= 160 && it.all { c -> c.code in 0x20..0x7E }, it) }
        val bobSms = texts(bob).single()
        assertTrue(bobSms.startsWith("Capital Invest: vous avez recu 25,00 \$ de Client +243970000601. Solde: 25,00 \$."), bobSms)

        val box = inbox(bob)
        assertEquals(1, box["unread"].asInt())
        assertEquals("TRANSFER_RECEIVED", box["items"][0]["kind"].asText())
        assertEquals("Argent reçu", box["items"][0]["title"].asText())
        assertEquals("Client +243970000601 vous a envoyé 25,00 \$ : « Pour l'école » Nouveau solde : 25,00 \$.", box["items"][0]["body"].asText())
        val tx = box["items"][0]["transactionId"].asText()
        // Le reçu lié s'ouvre depuis la notification.
        mvc.get("/v1/me/history/$tx") { header("Authorization", "Bearer ${bob.token}") }.andExpect { status { isOk() } }

        assertEquals(listOf("TRANSFER_SENT", "TOPUP"), inbox(alice)["items"].map { it["kind"].asText() })
        mvc.post("/v1/me/notifications/read") { header("Authorization", "Bearer ${bob.token}") }.andExpect { status { isNoContent() } }
        assertEquals(0, inbox(bob)["unread"].asInt())
        assertEquals(2, inbox(alice)["unread"].asInt(), "lire ses notifications ne touche pas celles des autres")
    }

    @Test
    fun `retrait verse puis retrait refuse et rembourse`() {
        val alice = register("+243970000603")
        topupConfirmed(alice, 10000, "n-top-2")
        fun withdraw(amount: Long, key: String) = mvc.post("/v1/withdrawals") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"AIRTEL","amountMinor":$amount,"pin":"2580"}"""
        }.body()["id"].asText()
        fun payout(id: String, success: Boolean) = mvc.post("/v1/simulators/mobile-money/payouts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"withdrawalId":"$id","success":$success}"""
        }.andExpect { status { isOk() } }

        payout(withdraw(2000, "n-w-1"), success = true)
        payout(withdraw(1000, "n-w-2"), success = false)
        val kinds = inbox(alice)["items"].map { it["kind"].asText() }
        assertEquals(listOf("WITHDRAWAL_REFUNDED", "WITHDRAWAL_PAID", "TOPUP"), kinds, "la demande de retrait seule ne notifie pas")
        val sent = texts(alice)
        assertEquals("Capital Invest: 20,00 \$ envoyes sur votre Airtel Money +243970000603.", sent[1])
        assertTrue(sent[2].startsWith("Capital Invest: retrait non verse. 10,25 \$ rendus, frais compris."), sent[2])
    }

    @Test
    fun `un SMS qui ne part pas est relance plus tard, puis abandonne apres 5 essais`() {
        val alice = register("+243970000604")
        sms.failuresToSimulate = 1
        topupConfirmed(alice, 1000, "n-top-3")
        assertEquals(emptyList(), texts(alice))
        assertEquals(0, dispatcher.dispatch(), "la relance attend une minute")
        testClock.advance(Duration.ofSeconds(61))
        assertEquals(1, dispatcher.dispatch())
        assertEquals(1, texts(alice).size)
        val row = jdbc.sql("SELECT sms_status || ' ' || sms_attempts FROM notification").query(String::class.java).single()
        assertEquals("SENT 2", row)

        sms.failuresToSimulate = 10
        topupConfirmed(alice, 1000, "n-top-4")
        repeat(5) {
            testClock.advance(Duration.ofMinutes(20))
            dispatcher.dispatch()
        }
        sms.failuresToSimulate = 0
        val failed = jdbc.sql("SELECT sms_status || ' ' || sms_attempts FROM notification WHERE sms_status <> 'SENT'")
            .query(String::class.java).single()
        assertEquals("FAILED 5", failed)
        // La notification reste visible dans l'app.
        assertEquals(2, inbox(login(alice.phone))["items"].size())
    }

    @Test
    fun `changer son PIN previent le client`() {
        val alice = register("+243970000605")
        mvc.post("/v1/auth/pin-reset/otp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"${alice.phone}"}"""
        }.andExpect { status { isAccepted() } }
        val session = mvc.post("/v1/auth/pin-reset") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"${alice.phone}","code":"${lastSmsCode(alice.phone)}","newPin":"3691","deviceId":"tel-2"}"""
        }.andExpect { status { isOk() } }.body()["accessToken"].asText()
        assertEquals("Capital Invest: votre PIN vient d'etre change. Si ce n'est pas vous, appelez vite le service client.", texts(alice).last())
        assertEquals("PIN_CHANGED", inbox(alice.copy(token = session))["items"][0]["kind"].asText())
    }
}
