package cd.capitalinvest.platform

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compte en francs congolais et change USD ↔ CDF, au taux provisoire de 2 850 FC pour 1 $ et 1 % de frais. */
class ExchangeApiTest : ApiTest() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var repository: LedgerRepository

    private fun exchange(s: Session, from: String, amountMinor: Long, key: String, rate: String? = null) =
        mvc.post("/v1/me/exchange") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"from":"$from","amountMinor":$amountMinor${rate?.let { ",\"rate\":$it" } ?: ""}}"""
        }

    private fun accounts(s: Session) =
        mvc.get("/v1/me/accounts") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()["accounts"]
            .associate { it["currency"].asText() to it["balanceMinor"].asLong() }

    private fun balanceOf(code: String) = repository.balance(repository.findAccountByCode(code)!!.id)

    @Test
    fun `chaque client a un compte CDF vide a cote du compte USD`() {
        val alice = register("+243970002001")
        val body = mvc.get("/v1/me/accounts") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isOk() } }.body()
        assertEquals(listOf("USD", "CDF"), body["accounts"].map { it["currency"].asText() })
        assertEquals(alice.walletId, body["accounts"][0]["accountId"].asText())
        assertEquals(mapOf("USD" to 0L, "CDF" to 0L), accounts(alice))

        mvc.get("/v1/me/exchange") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.cdfPerUsd") { value(2850) }
            jsonPath("$.feeBps") { value(100) }
        }
        mvc.get("/v1/me/exchange/quote?from=USD&amountMinor=1000") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.feeMinor") { value(10) }
            jsonPath("$.receivedMinor") { value(28_215) }
            jsonPath("$.to") { value("CDF") }
        }
    }

    @Test
    fun `changer des dollars en francs puis revenir, sans double debit au rejeu`() {
        val alice = register("+243970002002")
        topupConfirmed(alice, 2_000, "t-1")

        // 10,00 $ : 0,10 $ de frais, 9,90 $ changés à 2 850 = 28 215 FC.
        val done = exchange(alice, "USD", 1_000, "x-1", rate = "2850").andExpect { status { isOk() } }.body()
        assertEquals(10, done["quote"]["feeMinor"].asLong())
        assertEquals(28_215, done["quote"]["receivedMinor"].asLong())
        assertEquals(mapOf("USD" to 1_000L, "CDF" to 28_215L), accounts(alice))

        // Même clé : même change, rien ne bouge une seconde fois.
        val again = exchange(alice, "USD", 1_000, "x-1").andExpect { status { isOk() } }.body()
        assertEquals(done["transactionId"].asText(), again["transactionId"].asText())
        assertEquals(mapOf("USD" to 1_000L, "CDF" to 28_215L), accounts(alice))
        exchange(alice, "USD", 500, "x-1").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_KEY_REUSED") }
        }

        // Retour : 28 215 FC, 282 FC de frais, 27 933 FC changés = 9,80 $ (arrondi vers le bas).
        exchange(alice, "CDF", 28_215, "x-2").andExpect {
            status { isOk() }
            jsonPath("$.quote.feeMinor") { value(282) }
            jsonPath("$.quote.receivedMinor") { value(980) }
        }
        assertEquals(mapOf("USD" to 1_980L, "CDF" to 0L), accounts(alice))
        assertEquals(1_980, balance(alice))

        // Capital Invest garde les frais dans chaque devise ; la position de change porte le reste.
        assertEquals(10, balanceOf("FEES_REVENUE_USD"))
        assertEquals(282, balanceOf("FEES_REVENUE_CDF"))
        assertEquals(990 - 980, balanceOf("FX_POSITION_USD"))
        assertEquals(27_933 - 28_215, balanceOf("FX_POSITION_CDF"))

        // Chaque change est équilibré dans chaque devise.
        val unbalanced = jdbc.sql(
            """SELECT count(*) FROM (
                 SELECT e.transaction_id, e.currency FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
                 WHERE t.type = 'EXCHANGE' GROUP BY 1, 2
                 HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) <> 0) x""",
        ).query(Long::class.java).single()
        assertEquals(0, unbalanced)
    }

    @Test
    fun `refus clairs - solde insuffisant, trop petit, taux change, compte suspendu`() {
        val alice = register("+243970002003")
        topupConfirmed(alice, 1_000, "t-1")
        exchange(alice, "USD", 5_000, "x-1").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INSUFFICIENT_FUNDS") }
        }
        exchange(alice, "CDF", 1_000, "x-2").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INSUFFICIENT_FUNDS") }
        }
        exchange(alice, "USD", 50, "x-3").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("EXCHANGE_TOO_SMALL") }
        }
        // L'app montrait un autre taux : le client doit revoir le montant avant de confirmer.
        exchange(alice, "USD", 500, "x-4", rate = "2800").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("RATE_CHANGED") }
        }
        jdbc.sql("UPDATE customer SET status = 'SUSPENDED' WHERE id = :id::uuid").param("id", alice.customerId).update()
        exchange(alice, "USD", 500, "x-5").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("CUSTOMER_NOT_ACTIVE") }
        }
        assertEquals(mapOf("USD" to 1_000L, "CDF" to 0L), accounts(alice))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM currency_exchange").query(Long::class.java).single())
    }

    @Test
    fun `le change apparait dans l'historique de chaque compte, sans notification`() {
        val alice = register("+243970002004")
        topupConfirmed(alice, 2_000, "t-1")
        val done = exchange(alice, "USD", 1_000, "x-1").andExpect { status { isOk() } }.body()

        val usd = mvc.get("/v1/me/history") { header("Authorization", "Bearer ${alice.token}") }.body()["items"]
        assertEquals(listOf("EXCHANGE", "TOPUP"), usd.map { it["kind"].asText() })
        val row = usd[0]
        assertEquals(done["transactionId"].asText(), row["id"].asText())
        assertEquals(-1_000, row["netMinor"].asLong())
        assertEquals(10, row["feeMinor"].asLong())
        assertEquals(990, row["amountMinor"].asLong())
        assertEquals("CDF", row["exchange"]["to"].asText())
        assertEquals(28_215, row["exchange"]["toMinor"].asLong())
        assertEquals(2850, row["exchange"]["rate"].asInt())
        // Son propre compte CDF n'est pas « l'autre client ».
        assertTrue(row.path("counterpartyName").isMissingNode || row["counterpartyName"].isNull)

        val cdf = mvc.get("/v1/me/history?currency=CDF") { header("Authorization", "Bearer ${alice.token}") }.body()["items"]
        assertEquals(1, cdf.size())
        assertEquals("CDF", cdf[0]["currency"].asText())
        assertEquals(28_215, cdf[0]["netMinor"].asLong())
        assertEquals(0, cdf[0]["feeMinor"].asLong())

        mvc.get("/v1/me/history/${row["id"].asText()}") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.item.kind") { value("EXCHANGE") }
            jsonPath("$.item.exchange.fromMinor") { value(1_000) }
        }
        // Vu depuis le compte en francs, le même reçu montre l'arrivée en francs.
        mvc.get("/v1/me/history/${row["id"].asText()}?currency=CDF") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.item.netMinor") { value(28_215) }
            jsonPath("$.item.currency") { value("CDF") }
        }
        val notified = jdbc.sql("SELECT count(*) FROM notification WHERE transaction_id = :t::uuid")
            .param("t", done["transactionId"].asText()).query(Long::class.java).single()
        assertEquals(0, notified)
    }

    @Test
    fun `les francs comptent dans le solde maximal et bloquent la fermeture du compte`() {
        val alice = register("+243970002005")
        // Niveau 1 : 1 000 $ au plus, 200 $ par recharge.
        (1..5).forEach { topupConfirmed(alice, 20_000, "t-$it") }
        exchange(alice, "USD", 50_000, "x-1").andExpect { status { isOk() } }
        // 500 $ restent en dollars et environ 495 $ en francs : une nouvelle recharge de 200 $ dépasserait 1 000 $.
        mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "t-6")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"MPESA","amountMinor":20000}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("LIMIT_MAX_BALANCE") }
        }

        val bob = register("+243970002006")
        topupConfirmed(bob, 2_000, "bob-t-1")
        exchange(bob, "USD", 2_000, "x-1").andExpect { status { isOk() } }
        assertEquals(0, balance(bob))
        mvc.get("/v1/me/closure") { header("Authorization", "Bearer ${bob.token}") }.andExpect {
            status { isOk() }
            jsonPath("$.canClose") { value(false) }
            jsonPath("$.blockers[0].code") { value("CDF_BALANCE_NOT_EMPTY") }
        }
    }

    @Test
    fun `le grand livre n'accepte deux devises que pour un change equilibre dans chacune`() {
        val alice = register("+243970002007")
        val usd = UUID.fromString(alice.walletId)
        val cdf = repository.findCustomerWallet(UUID.fromString(alice.customerId), cd.capitalinvest.platform.common.Currency.CDF)!!.id
        val fxUsd = repository.findAccountByCode("FX_POSITION_USD")!!.id
        val fxCdf = repository.findAccountByCode("FX_POSITION_CDF")!!.id
        topupConfirmed(alice, 1_000, "t-1")

        val mixed = assertThrows<BusinessException> {
            ledger.post(PostingRequest("k-1", "EXCHANGE", null, listOf(PostingLine(usd, Direction.DEBIT, 100), PostingLine(cdf, Direction.CREDIT, 100))))
        }
        assertEquals("CURRENCY_MISMATCH", mixed.code)

        val unbalanced = assertThrows<BusinessException> {
            ledger.post(
                PostingRequest(
                    "k-2", "EXCHANGE", null,
                    listOf(
                        PostingLine(usd, Direction.DEBIT, 100), PostingLine(fxUsd, Direction.CREDIT, 100),
                        PostingLine(fxCdf, Direction.DEBIT, 2_850), PostingLine(cdf, Direction.CREDIT, 2_800),
                    ),
                    crossCurrency = true,
                ),
            )
        }
        assertEquals("UNBALANCED", unbalanced.code)
        assertEquals(1_000, balance(alice))
    }
}
