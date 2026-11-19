package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

class AirtimeApiTest : ApiTest() {

    private fun buy(s: Session, phone: String, amountMinor: Long, key: String, pin: String = "2580", network: String? = null) =
        mvc.post("/v1/airtime") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"$phone","amountMinor":$amountMinor,"pin":"$pin"${network?.let { ""","network":"$it"""" } ?: ""}}"""
        }

    @Test
    fun `credit pour un proche, reseau devine, PIN, rejeu, plafond journalier`() {
        val alice = register("+243970001101")
        topupConfirmed(alice, 10_000, "t-1")

        buy(alice, "0812345678", 500, "a-1", pin = "1111").andExpect { status { isUnauthorized() } }
        val bought = buy(alice, "0812345678", 500, "a-1").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("DELIVERED") }
            jsonPath("$.network") { value("VODACOM") }
            jsonPath("$.phone") { value("+243812345678") }
        }.body()
        // Même clé : un seul achat.
        buy(alice, "0812345678", 500, "a-1").andExpect { jsonPath("$.id") { value(bought["id"].asText()) } }
        assertEquals(9_500, balance(alice))

        buy(alice, "+243 970 001 101", 200, "a-2").andExpect { jsonPath("$.network") { value("AIRTEL") } }
        buy(alice, "0610000000", 200, "a-3").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("NETWORK_REQUIRED") }
        }
        buy(alice, "0812345678", 10, "a-4").andExpect { jsonPath("$.code") { value("AIRTIME_AMOUNT") } }
        buy(alice, "0812345678", 6_000, "a-5").andExpect { jsonPath("$.code") { value("AIRTIME_AMOUNT") } }

        val receipt = mvc.get("/v1/me/history/${bought["transactionId"].asText()}") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }.body()["item"]
        assertEquals("AIRTIME", receipt["kind"].asText())
        assertEquals("VODACOM", receipt["network"].asText())
        assertEquals("+243812345678", receipt["msisdn"].asText())
        assertEquals(0, receipt["feeMinor"].asInt())

        val recent = mvc.get("/v1/airtime/recent") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(listOf("+243970001101", "+243812345678"), recent.map { it["phone"].asText() })

        // Le crédit compte dans le plafond journalier d'envoi.
        val limits = mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(700, limits["dailyOutUsedMinor"].asLong())
    }

    @Test
    fun `numero refuse par l'operateur, le client est rembourse et prevenu`() {
        val bob = register("+243970001102")
        topupConfirmed(bob, 3_000, "t-1")
        val failed = buy(bob, "0990000000", 1_000, "b-1").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.failureMessage") { value("Ce numéro n'est pas actif chez Airtel") }
        }.body()
        assertEquals(3_000, balance(bob))

        val items = mvc.get("/v1/me/history") { header("Authorization", "Bearer ${bob.token}") }.body()["items"]
        assertEquals(listOf("AIRTIME_REFUND", "AIRTIME", "TOPUP"), items.map { it["kind"].asText() })
        assertEquals("FAILED", items[1]["status"].asText())
        assertEquals(failed["transactionId"].asText(), items[1]["id"].asText())
        val notes = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${bob.token}") }.body()["items"]
        assertEquals("AIRTIME_REFUNDED", notes[0]["kind"].asText())
        assertEquals(
            "Le crédit pour +243990000000 n'a pas pu être envoyé : Ce numéro n'est pas actif chez Airtel. 10,00 \$ sont revenus sur votre compte.",
            notes[0]["body"].asText(),
        )
        // Un achat remboursé ne compte pas dans le plafond du jour.
        val limits = mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${bob.token}") }.body()
        assertEquals(0, limits["dailyOutUsedMinor"].asLong())
    }
}
