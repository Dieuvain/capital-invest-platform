package cd.capitalinvest.platform

import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.history.HistoryItem
import cd.capitalinvest.platform.history.HistoryKind
import cd.capitalinvest.platform.history.HistoryStatus
import cd.capitalinvest.platform.history.SpendingCategory
import cd.capitalinvest.platform.history.SpendingService
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpendingApiTest : ApiTest() {
    private val zone = ZoneId.of("Africa/Lubumbashi")

    private fun item(
        kind: HistoryKind, net: Long, fee: Long = 0, status: HistoryStatus = HistoryStatus.COMPLETED,
        name: String? = null, phone: String? = null,
    ) = HistoryItem(
        UUID.randomUUID(), "CI-1", kind, status, Math.abs(net) - fee, fee, net, Currency.USD, OffsetDateTime.now(),
        name, phone, null, null, note = null, failureMessage = null,
    )

    @Test
    fun `depenses par categorie, frais a part, remboursements deduits, echecs ignores, epargne a part`() {
        val now = listOf(
            item(HistoryKind.TOPUP, 50_000),
            item(HistoryKind.TRANSFER_RECEIVED, 4_500),
            item(HistoryKind.TRANSFER_SENT, -15_150, fee = 150, name = "Béni M.", phone = "+243 97• ••• 402"),
            item(HistoryKind.TRANSFER_SENT, -2_020, fee = 20, name = "Josué K.", phone = "+243 97• ••• 002"),
            item(HistoryKind.TRANSFER_SENT, -5_050, fee = 50, name = "Béni M.", phone = "+243 97• ••• 402"),
            item(HistoryKind.BILL_PAYMENT, -2_500),
            item(HistoryKind.AIRTIME, -500),
            item(HistoryKind.AIRTIME_REFUND, 500),
            item(HistoryKind.AIRTIME, -1_000),
            item(HistoryKind.WITHDRAWAL, -10_150, fee = 150, status = HistoryStatus.FAILED),
            item(HistoryKind.SAVINGS_DEPOSIT, -5_000),
            item(HistoryKind.SAVINGS_WITHDRAWAL, 1_000),
        )
        val before = listOf(
            item(HistoryKind.TRANSFER_SENT, -10_100, fee = 100),
            item(HistoryKind.MERCHANT_PAYMENT, -3_000),
        )
        val s = SpendingService.summarize(
            YearMonth.of(2026, 11), true, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 19), now, before,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19),
        )
        val c = s.categories.associateBy { it.category }
        assertEquals(listOf(SpendingCategory.TRANSFERS, SpendingCategory.BILLS, SpendingCategory.AIRTIME, SpendingCategory.FEES, SpendingCategory.MERCHANTS),
            s.categories.map { it.category })
        assertEquals(22_000, c.getValue(SpendingCategory.TRANSFERS).amountMinor)
        assertEquals(3, c.getValue(SpendingCategory.TRANSFERS).count)
        assertEquals(10_000, c.getValue(SpendingCategory.TRANSFERS).previousMinor)
        assertEquals(1_000, c.getValue(SpendingCategory.AIRTIME).amountMinor) // 5 $ remboursés
        assertEquals(2, c.getValue(SpendingCategory.AIRTIME).count)
        assertEquals(220, c.getValue(SpendingCategory.FEES).amountMinor)
        assertEquals(0, c.getValue(SpendingCategory.MERCHANTS).amountMinor) // seulement le mois d'avant
        assertEquals(null, c[SpendingCategory.WITHDRAWALS]) // retrait échoué : rien n'est parti
        assertEquals(22_000 + 2_500 + 1_000 + 220, s.spentMinor)
        assertEquals(13_100, s.previousSpentMinor)
        assertEquals(54_500, s.receivedMinor)
        assertEquals(4_000, s.savedMinor)
        assertEquals(listOf("Béni M." to 20_000L, "Josué K." to 2_000L), s.topRecipients.map { it.name to it.amountMinor })
        assertEquals(2, s.topRecipients[0].count)
    }

    @Test
    fun `mois en cours par l API, sans comparaison pour un compte tout neuf`() {
        val alice = register("+243970001701")
        val bob = register("+243970001702")
        topupConfirmed(alice, 10_000, "t-1")
        transfer(alice, bob.phone, 2_000, "e-1")
        val s = mvc.get("/v1/me/spending") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(YearMonth.now(zone).toString(), s["month"].asText())
        assertEquals(10_000, s["receivedMinor"].asLong())
        assertEquals(2_020, s["spentMinor"].asLong())
        assertEquals("TRANSFERS", s["categories"][0]["category"].asText())
        assertEquals("Client +243970001702", s["topRecipients"][0]["name"].asText())
        assertEquals(true, s["topRecipients"][0]["phone"].asText().contains("•"))
        // Le compte vient d'ouvrir : pas de mois d'avant à comparer.
        assertNull(s["comparedFrom"].takeUnless { it.isNull })
        assertNull(s["previousSpentMinor"].takeUnless { it.isNull })

        mvc.get("/v1/me/spending?month=juin") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { jsonPath("$.code") { value("INVALID_MONTH") } }
    }
}
