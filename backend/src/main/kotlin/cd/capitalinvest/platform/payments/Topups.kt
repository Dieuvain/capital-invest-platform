package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

enum class Operator(val settlementAccountCode: String) {
    MPESA("SETTLEMENT_MPESA_USD"),
    AIRTEL("SETTLEMENT_AIRTEL_USD"),
    ORANGE("SETTLEMENT_ORANGE_USD"),
}

enum class TopupStatus { PENDING, CONFIRMED, FAILED }

data class Topup(
    val id: UUID,
    val idempotencyKey: String,
    val customerId: UUID,
    val operator: Operator,
    val amountMinor: Long,
    val currency: Currency,
    val status: TopupStatus,
    val operatorReference: String?,
    val ledgerTransactionId: UUID?,
)

@Repository
class TopupRepository(private val jdbc: JdbcClient) {
    fun insertIfAbsent(t: Topup): Boolean =
        jdbc.sql(
            """INSERT INTO topup (id, idempotency_key, customer_id, operator, amount_minor, currency, status)
               VALUES (:id, :key, :customer, :operator, :amount, :currency, :status)
               ON CONFLICT (idempotency_key) DO NOTHING""",
        )
            .param("id", t.id).param("key", t.idempotencyKey).param("customer", t.customerId)
            .param("operator", t.operator.name).param("amount", t.amountMinor)
            .param("currency", t.currency.name).param("status", t.status.name)
            .update() == 1

    fun findByKey(key: String): Topup? =
        jdbc.sql("SELECT * FROM topup WHERE idempotency_key = :k").param("k", key).query(::map).optional().orElse(null)

    fun findById(id: UUID): Topup? =
        jdbc.sql("SELECT * FROM topup WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    fun lock(id: UUID): Topup? =
        jdbc.sql("SELECT * FROM topup WHERE id = :id FOR UPDATE").param("id", id).query(::map).optional().orElse(null)

    fun markFinal(id: UUID, status: TopupStatus, reference: String?, ledgerTransactionId: UUID?) {
        jdbc.sql(
            """UPDATE topup SET status = :status, operator_reference = :ref, ledger_transaction_id = :tx, updated_at = now()
               WHERE id = :id""",
        ).param("status", status.name).param("ref", reference).param("tx", ledgerTransactionId).param("id", id).update()
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Topup(
        id = rs.getObject("id", UUID::class.java),
        idempotencyKey = rs.getString("idempotency_key"),
        customerId = rs.getObject("customer_id", UUID::class.java),
        operator = Operator.valueOf(rs.getString("operator")),
        amountMinor = rs.getLong("amount_minor"),
        currency = Currency.valueOf(rs.getString("currency")),
        status = TopupStatus.valueOf(rs.getString("status")),
        operatorReference = rs.getString("operator_reference"),
        ledgerTransactionId = rs.getObject("ledger_transaction_id", UUID::class.java),
    )
}

/** Demande de paiement envoyée à l'opérateur. Le vrai connecteur remplacera le simulateur. */
interface MobileMoneyGateway {
    fun requestPayment(topup: Topup, phone: String)
}

@Service
class SimulatedMobileMoneyGateway : MobileMoneyGateway {
    // Le simulateur ne fait rien ici : la confirmation arrive par l'endpoint de simulation,
    // comme le ferait le webhook de l'opérateur.
    override fun requestPayment(topup: Topup, phone: String) = Unit
}

@Service
class TopupService(
    private val topups: TopupRepository,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val gateway: MobileMoneyGateway,
    private val limits: LimitsService,
) {

    /** Crée la recharge à l'état PENDING. Rejouer la même clé renvoie la même recharge. */
    @Transactional
    fun initiate(idempotencyKey: String, customerId: UUID, operator: Operator, amountMinor: Long): Topup {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Client suspendu ou fermé")

        topups.findByKey(idempotencyKey)?.let { return replay(it, customerId, operator, amountMinor) }

        // Verrou du portefeuille : deux recharges simultanées ne peuvent pas dépasser le solde maximal ensemble.
        val wallet = ledgerRepository.findCustomerWallet(customerId, Currency.USD)!!
        ledgerRepository.lockAccounts(listOf(wallet.id))
        limits.checkTopup(customer, wallet.id, amountMinor)

        val candidate = Topup(
            UUID.randomUUID(), idempotencyKey, customerId, operator, amountMinor, Currency.USD,
            TopupStatus.PENDING, null, null,
        )
        if (!topups.insertIfAbsent(candidate)) return replay(topups.findByKey(idempotencyKey)!!, customerId, operator, amountMinor)
        gateway.requestPayment(candidate, customer.phone)
        return candidate
    }

    private fun replay(existing: Topup, customerId: UUID, operator: Operator, amountMinor: Long): Topup {
        if (existing.customerId != customerId || existing.operator != operator || existing.amountMinor != amountMinor) {
            throw ConflictException("IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour une autre recharge")
        }
        return existing
    }

    /**
     * Applique la réponse de l'opérateur. Aucun crédit sans confirmation ;
     * une notification en double ne crédite jamais deux fois.
     */
    @Transactional
    fun applyOperatorResult(topupId: UUID, success: Boolean, operatorReference: String?): Topup {
        val topup = topups.lock(topupId) ?: throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
        val target = if (success) TopupStatus.CONFIRMED else TopupStatus.FAILED
        if (topup.status != TopupStatus.PENDING) {
            if (topup.status == target) return topup
            throw ConflictException("TOPUP_ALREADY_FINAL", "Recharge déjà à l'état ${topup.status}")
        }
        if (!success) {
            topups.markFinal(topupId, TopupStatus.FAILED, operatorReference, null)
            return topups.findById(topupId)!!
        }
        val settlement = ledgerRepository.findAccountByCode(topup.operator.settlementAccountCode)
            ?: error("Compte de règlement absent : ${topup.operator.settlementAccountCode}")
        val wallet = ledgerRepository.findCustomerWallet(topup.customerId, topup.currency)
            ?: error("Portefeuille absent pour ${topup.customerId}")
        val result = ledger.post(
            PostingRequest(
                idempotencyKey = "topup:${topup.id}",
                type = "TOPUP",
                description = "Recharge ${topup.operator} ${operatorReference ?: ""}".trim(),
                lines = listOf(
                    PostingLine(settlement.id, Direction.DEBIT, topup.amountMinor),
                    PostingLine(wallet.id, Direction.CREDIT, topup.amountMinor),
                ),
            ),
        )
        topups.markFinal(topupId, TopupStatus.CONFIRMED, operatorReference, result.transaction.id)
        return topups.findById(topupId)!!
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Topup = topups.findById(id) ?: throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
}
