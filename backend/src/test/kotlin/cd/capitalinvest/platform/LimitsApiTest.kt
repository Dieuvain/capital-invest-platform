package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.assertEquals

/** Plafonds KYC 1 (valeurs provisoires) : 200 $ par opération, 600 $ envoyés par jour, solde maximal 1 000 $. */
class LimitsApiTest : ApiTest() {

    private fun startTopup(s: Session, amountMinor: Long, key: String) = mvc.post("/v1/topups") {
        header("Authorization", "Bearer ${s.token}")
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"operator":"MPESA","amountMinor":$amountMinor}"""
    }

    @Test
    fun `une recharge au-dessus du plafond par operation est refusee`() {
        val alice = register("+243970000401")
        startTopup(alice, 20_001, "t-1").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("LIMIT_PER_TRANSACTION") }
        }
        startTopup(alice, 20_000, "t-2").andExpect { status { isAccepted() } }
    }

    @Test
    fun `le solde maximal compte aussi les recharges en attente`() {
        val alice = register("+243970000402")
        topupConfirmed(alice, 20_000, "t-1")
        topupConfirmed(alice, 20_000, "t-2")
        repeat(3) { startTopup(alice, 20_000, "p-$it").andExpect { status { isAccepted() } } } // 400 $ + 600 $ en attente
        startTopup(alice, 1, "p-trop").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("LIMIT_MAX_BALANCE") }
        }
        // Rejouer une recharge déjà acceptée reste possible.
        startTopup(alice, 20_000, "p-0").andExpect { status { isAccepted() } }
    }

    @Test
    fun `plafond journalier d'envoi, remis a zero le lendemain`() {
        val alice = register("+243970000403")
        val bob = register("+243970000404")
        setKycLevel(bob, 2)
        repeat(4) { topupConfirmed(alice, 20_000, "t-$it") }

        repeat(3) { transfer(alice, bob.phone, 20_000, "e-$it").andExpect { status { isCreated() } } }
        transfer(alice, bob.phone, 1, "e-trop").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("LIMIT_DAILY") }
        }
        // Un rejeu de la même clé n'est pas compté une deuxième fois.
        transfer(alice, bob.phone, 20_000, "e-2").andExpect { status { is2xxSuccessful() } }
        assertEquals(20_000, balance(alice))

        mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.kycLevel") { value(1) }
            jsonPath("$.dailyOutUsedMinor") { value(60_000) }
            jsonPath("$.dailyOutRemainingMinor") { value(0) }
        }

        testClock.advance(Duration.ofDays(1))
        val again = login(alice.phone)
        transfer(again, bob.phone, 20_000, "e-lendemain").andExpect { status { isCreated() } }
    }

    @Test
    fun `un destinataire au solde maximal ne peut pas recevoir`() {
        val alice = register("+243970000405")
        val bob = register("+243970000406")
        setKycLevel(alice, 2)
        topupConfirmed(alice, 40_000, "t-a")
        repeat(4) { topupConfirmed(bob, 20_000, "t-b$it") } // bob : 800 $ sur 1 000 $

        transfer(alice, bob.phone, 20_000, "e-1").andExpect { status { isCreated() } }
        transfer(alice, bob.phone, 1, "e-2").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("RECIPIENT_LIMIT_REACHED") }
        }
        assertEquals(100_000, balance(bob))
    }

    @Test
    fun `le niveau KYC 2 releve les plafonds`() {
        val alice = register("+243970000407")
        setKycLevel(alice, 2)
        startTopup(alice, 300_000, "t-1").andExpect { status { isAccepted() } }
    }
}
