package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillsApiTest : ApiTest() {

    private fun lookup(s: Session, biller: String, reference: String) =
        mvc.post("/v1/bills/lookup") {
            header("Authorization", "Bearer ${s.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"biller":"$biller","reference":"$reference"}"""
        }

    private fun pay(s: Session, biller: String, reference: String, amountMinor: Long, key: String, pin: String = "2580") =
        mvc.post("/v1/bills") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"biller":"$biller","reference":"$reference","amountMinor":$amountMinor,"pin":"$pin"}"""
        }

    @Test
    fun `compteur SNEL prepaye, verifie puis paye, code par SMS`() {
        val alice = register("+243970001201")
        topupConfirmed(alice, 10_000, "t-1")
        val billers = mvc.get("/v1/bills/billers") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(listOf("SNEL_PREPAID", "REGIDESO", "CANAL_PLUS", "DSTV"), billers.map { it["code"].asText() })

        lookup(alice, "SNEL_PREPAID", "1234").andExpect { jsonPath("$.code") { value("INVALID_BILL_REFERENCE") } }
        lookup(alice, "SNEL_PREPAID", "01234560000").andExpect { status { isNotFound() }; jsonPath("$.code") { value("BILL_ACCOUNT_NOT_FOUND") } }
        val account = lookup(alice, "SNEL_PREPAID", "0123 4567 891").andExpect { status { isOk() } }.body()
        assertEquals("01234567891", account["reference"].asText())
        assertTrue(account["holderName"].asText().isNotBlank())
        assertTrue(account["amountDueMinor"].isNull)

        pay(alice, "SNEL_PREPAID", "01234567891", 2_000, "b-1", pin = "1111").andExpect { status { isUnauthorized() } }
        val paid = pay(alice, "SNEL_PREPAID", "01234567891", 2_000, "b-1").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("PAID") }
            jsonPath("$.billerName") { value("SNEL prépayé") }
        }.body()
        val token = paid["token"].asText()
        assertTrue(Regex("^\\d{4}( \\d{4}){4}$").matches(token), token)
        assertEquals(account["holderName"].asText(), paid["holderName"].asText())
        pay(alice, "SNEL_PREPAID", "01234567891", 2_000, "b-1").andExpect { jsonPath("$.id") { value(paid["id"].asText()) } }
        assertEquals(8_000, balance(alice))

        val item = mvc.get("/v1/me/history/${paid["transactionId"].asText()}") { header("Authorization", "Bearer ${alice.token}") }
            .body()["item"]
        assertEquals("BILL_PAYMENT", item["kind"].asText())
        assertEquals("SNEL prépayé", item["counterpartyName"].asText())
        assertEquals(token, item["token"].asText())
        assertEquals("01234567891", item["billReference"].asText())

        val sms = jdbc.sql("SELECT sms_text FROM notification WHERE kind = 'BILL_PAID'").query(String::class.java).single()
        assertTrue(sms.endsWith("Code: $token"), sms)
        val recent = mvc.get("/v1/bills/recent") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals("01234567891", recent[0]["reference"].asText())
        val limits = mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(2_000, limits["dailyOutUsedMinor"].asLong())
    }

    @Test
    fun `facture REGIDESO refusee par le facturier, client rembourse`() {
        val bob = register("+243970001202")
        topupConfirmed(bob, 5_000, "t-1")
        val account = lookup(bob, "REGIDESO", "12349999").andExpect { status { isOk() } }.body()
        assertTrue(account["amountDueMinor"].asLong() > 0)
        pay(bob, "REGIDESO", "12349999", 50, "b-0").andExpect { jsonPath("$.code") { value("BILL_AMOUNT") } }
        val failed = pay(bob, "REGIDESO", "12349999", 1_500, "b-1").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("FAILED") }
        }.body()
        assertNull(failed["token"].textValue())
        assertEquals(5_000, balance(bob))
        val items = mvc.get("/v1/me/history?category=PAYMENT") { header("Authorization", "Bearer ${bob.token}") }.body()["items"]
        assertEquals(listOf("BILL_REFUND", "BILL_PAYMENT"), items.map { it["kind"].asText() })
        assertEquals("FAILED", items[1]["status"].asText())
        val note = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${bob.token}") }.body()["items"][0]
        assertEquals("BILL_REFUNDED", note["kind"].asText())
        assertEquals(
            "Votre paiement REGIDESO n'a pas abouti : REGIDESO n'accepte pas de paiement pour ce compte. 15,00 \$ sont revenus sur votre compte.",
            note["body"].asText(),
        )
    }
}
