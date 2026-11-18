package cd.capitalinvest.platform

import cd.capitalinvest.platform.payments.FeeProperties
import cd.capitalinvest.platform.payments.FeeService
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

class FeesTest : ApiTest() {

    private val fees = FeeService(FeeProperties(transferBps = 100, transferMinMinor = 10, transferMaxMinor = 500))

    @Test
    fun `1 pourcent, au moins 10 centimes, au plus 5 dollars`() {
        assertEquals(10, fees.transferFee(1))          // 0,01 $ -> minimum
        assertEquals(10, fees.transferFee(1_000))      // 10 $ -> 0,10 $
        assertEquals(13, fees.transferFee(1_250))      // 12,50 $ -> 0,125 arrondi à 0,13 $
        assertEquals(200, fees.transferFee(20_000))    // 200 $ -> 2 $
        assertEquals(500, fees.transferFee(400_000))   // 4 000 $ -> plafonné à 5 $
    }

    @Test
    fun `les frais vont au compte de revenus et le solde doit couvrir montant plus frais`() {
        val alice = register("+243970000601")
        val bob = register("+243970000602")
        topupConfirmed(alice, 1_000, "t-1")
        // 10 $ + 0,10 $ de frais > 10 $ de solde.
        transfer(alice, bob.phone, 1_000, "e-1").andExpect { jsonPath("$.code") { value("INSUFFICIENT_FUNDS") } }
        transfer(alice, bob.phone, 990, "e-2").andExpect { status { isCreated() } }
        assertEquals(0, balance(alice))
        assertEquals(990, balance(bob))
        val revenue = jdbc.sql(
            """SELECT COALESCE(SUM(e.amount_minor), 0) FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id
               WHERE a.code = 'FEES_REVENUE_USD' AND e.direction = 'CREDIT'""",
        ).query(Long::class.java).single()
        assertEquals(10, revenue)
    }

    @Test
    fun `le client voit les frais avant de confirmer`() {
        val alice = register("+243970000603")
        mvc.get("/v1/transfers/fee?amountMinor=20000") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.feeMinor") { value(200) }
            jsonPath("$.totalMinor") { value(20_200) }
        }
    }
}
