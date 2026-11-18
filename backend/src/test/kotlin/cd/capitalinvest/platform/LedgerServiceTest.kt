package cd.capitalinvest.platform

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.customers.CustomerService
import cd.capitalinvest.platform.customers.Language
import cd.capitalinvest.platform.ledger.Direction.CREDIT
import cd.capitalinvest.platform.ledger.Direction.DEBIT
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerServiceTest : DatabaseTest() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var repository: LedgerRepository
    @Autowired lateinit var customers: CustomerService

    private val mpesa get() = repository.findAccountByCode("SETTLEMENT_MPESA_USD")!!.id

    private fun wallet(phone: String) = customers.open(phone, "Client test", Language.fr).walletAccountId

    private fun fund(walletId: UUID, amount: Long) = ledger.post(
        PostingRequest("fund:$walletId:$amount", "TOPUP", null, listOf(PostingLine(mpesa, DEBIT, amount), PostingLine(walletId, CREDIT, amount))),
    )

    @Test
    fun `une recharge credite le portefeuille et debite le compte de reglement`() {
        val w = wallet("+243970000001")
        fund(w, 10_000)
        assertEquals(10_000, ledger.balance(w))
        assertEquals(10_000, ledger.balance(mpesa))
    }

    @Test
    fun `une ecriture desequilibree est refusee`() {
        val w = wallet("+243970000002")
        val e = assertFailsWith<BusinessException> {
            ledger.post(PostingRequest("k1", "TEST", null, listOf(PostingLine(mpesa, DEBIT, 100), PostingLine(w, CREDIT, 99))))
        }
        assertEquals("UNBALANCED", e.code)
    }

    @Test
    fun `un portefeuille ne passe jamais sous zero et rien n'est ecrit`() {
        val a = wallet("+243970000003")
        val b = wallet("+243970000004")
        fund(a, 500)
        val e = assertFailsWith<BusinessException> {
            ledger.post(PostingRequest("k2", "TRANSFER", null, listOf(PostingLine(a, DEBIT, 501), PostingLine(b, CREDIT, 501))))
        }
        assertEquals("INSUFFICIENT_FUNDS", e.code)
        assertEquals(500, ledger.balance(a))
        assertEquals(0, ledger.balance(b))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM ledger_transaction WHERE idempotency_key = 'k2'").query(Long::class.java).single())
    }

    @Test
    fun `rejouer la meme cle renvoie la meme transaction sans double debit`() {
        val a = wallet("+243970000005")
        val b = wallet("+243970000006")
        fund(a, 1_000)
        val req = PostingRequest("k3", "TRANSFER", null, listOf(PostingLine(a, DEBIT, 300), PostingLine(b, CREDIT, 300)))
        val first = ledger.post(req)
        val second = ledger.post(req)
        assertEquals(first.transaction.id, second.transaction.id)
        assertTrue(second.replayed)
        assertEquals(700, ledger.balance(a))
    }

    @Test
    fun `reutiliser une cle pour une autre operation est refuse`() {
        val a = wallet("+243970000007")
        val b = wallet("+243970000008")
        fund(a, 1_000)
        ledger.post(PostingRequest("k4", "TRANSFER", null, listOf(PostingLine(a, DEBIT, 300), PostingLine(b, CREDIT, 300))))
        assertFailsWith<ConflictException> {
            ledger.post(PostingRequest("k4", "TRANSFER", null, listOf(PostingLine(a, DEBIT, 400), PostingLine(b, CREDIT, 400))))
        }
    }

    @Test
    fun `des envois simultanes ne creent jamais d'argent ni de decouvert`() {
        val a = wallet("+243970000009")
        val b = wallet("+243970000010")
        fund(a, 10_000)
        val pool = Executors.newFixedThreadPool(10)
        val results = (1..20).map { i ->
            pool.submit(Callable {
                runCatching {
                    ledger.post(PostingRequest("conc-$i", "TRANSFER", null, listOf(PostingLine(a, DEBIT, 3_000), PostingLine(b, CREDIT, 3_000))))
                }.isSuccess
            })
        }.map { it.get() }
        pool.shutdown()
        assertEquals(3, results.count { it })
        assertEquals(1_000, ledger.balance(a))
        assertEquals(9_000, ledger.balance(b))
    }
}
