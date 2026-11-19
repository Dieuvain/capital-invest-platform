package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatementsApiTest : ApiTest() {

    private val zone = ZoneId.of("Africa/Lubumbashi")

    @Test
    fun `releve du mois passe et du mois en cours, soldes enchaines, PDF telechargeable`() {
        val alice = register("+243970000901")
        val bob = register("+243970000902")
        topupConfirmed(alice, 10_000, "t-1")
        transfer(alice, bob.phone, 2_000, "e-1").andExpect { status { isCreated() } }

        // Ces deux opérations datent du mois dernier, comme le compte.
        val current = YearMonth.now(zone)
        val last = current.minusMonths(1)
        val lastDay = last.atDay(15).atStartOfDay(zone).toOffsetDateTime()
        // Une heure d'écart : l'ordre des lignes ne dépend pas de l'identifiant des opérations.
        jdbc.sql("UPDATE ledger_transaction SET created_at = :at + CASE WHEN type = 'TOPUP' THEN interval '0' ELSE interval '1 hour' END")
            .param("at", lastDay).update()
        jdbc.sql("UPDATE ledger_account SET created_at = :at WHERE customer_id = :c::uuid").param("at", lastDay).param("c", alice.customerId).update()

        topupConfirmed(alice, 5_000, "t-2")
        transfer(bob.let { login(it.phone) }, alice.phone, 1_500, "e-2").andExpect { status { isCreated() } }

        val months = mvc.get("/v1/me/statements") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }.body()
        assertEquals(listOf(current.toString(), last.toString()), months.map { it["month"].asText() })
        assertEquals(listOf(true, false), months.map { it["partial"].asBoolean() })

        val before = mvc.get("/v1/me/statements/$last") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }.body()
        assertEquals(0, before["openingBalanceMinor"].asLong())
        assertEquals(10_000, before["totalInMinor"].asLong())
        val fee = before["feesMinor"].asLong()
        assertTrue(fee > 0)
        assertEquals(2_000 + fee, before["totalOutMinor"].asLong())
        assertEquals(8_000 - fee, before["closingBalanceMinor"].asLong())
        assertEquals(listOf("TOPUP", "TRANSFER_SENT"), before["items"].map { it["kind"].asText() })
        assertEquals(last.atEndOfMonth().toString(), before["to"].asText())

        val now = mvc.get("/v1/me/statements/$current") { header("Authorization", "Bearer ${alice.token}") }.body()
        assertEquals(before["closingBalanceMinor"].asLong(), now["openingBalanceMinor"].asLong())
        assertEquals(balance(alice), now["closingBalanceMinor"].asLong())
        assertEquals(LocalDate.now(zone).toString(), now["to"].asText())
        assertEquals(true, now["partial"].asBoolean())

        val pdf = mvc.get("/v1/me/statements/$last/pdf") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect {
                status { isOk() }
                content { contentType("application/pdf") }
                header { string("Content-Disposition", "attachment; filename=\"releve-capital-invest-$last.pdf\"") }
            }.andReturn().response.contentAsByteArray
        assertEquals("%PDF", String(pdf.copyOf(4)))

        mvc.get("/v1/me/statements/${current.plusMonths(1)}") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("STATEMENT_UNAVAILABLE") } }
        mvc.get("/v1/me/statements/novembre") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { jsonPath("$.code") { value("INVALID_MONTH") } }
        // Bob n'a qu'un mois : le sien.
        assertEquals(1, mvc.get("/v1/me/statements") { header("Authorization", "Bearer ${bob.token}") }.body().size())
    }
}
