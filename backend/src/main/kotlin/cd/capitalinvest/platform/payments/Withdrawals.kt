package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.notifications.NotificationService
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.payments.mobilemoney.CheckResult
import cd.capitalinvest.platform.payments.mobilemoney.InitiateResult
import cd.capitalinvest.platform.payments.mobilemoney.MobileMoneyProperties
import cd.capitalinvest.platform.payments.mobilemoney.MobileMoneyRouter
import cd.capitalinvest.platform.payments.mobilemoney.PaymentRequest
import com.fasterxml.jackson.annotation.JsonIgnore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Retrait vers mobile money, tel que le client le voit (les champs internes ne sortent pas).
 * Sert aussi au reversement d'un marchand : le propriétaire est alors le marchand, pas un client.
 */
data class Withdrawal(
    val id: UUID,
    @JsonIgnore val idempotencyKey: String,
    @JsonIgnore val customerId: UUID?,
    val operator: Operator,
    /** Numéro qui reçoit l'argent, au format international sans « + ». */
    val msisdn: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val currency: Currency,
    val status: TopupStatus,
    val operatorReference: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: OffsetDateTime,
    @JsonIgnore val provider: String,
    @JsonIgnore val settlementCode: String,
    @JsonIgnore val checks: Int,
    @JsonIgnore val reviewReason: String?,
    @JsonIgnore val merchantId: UUID? = null,
)

@Repository
class WithdrawalRepository(private val jdbc: JdbcClient) {
    fun insert(w: Withdrawal, holdTransactionId: UUID, nextCheckAt: OffsetDateTime, requestedBy: UUID? = null) {
        jdbc.sql(
            """INSERT INTO withdrawal (id, idempotency_key, customer_id, merchant_id, requested_by, operator, msisdn, amount_minor, fee_minor, currency,
                                       status, provider, settlement_code, hold_transaction_id, next_check_at, created_at, updated_at)
               VALUES (:id, :key, :customer, :merchant, :by, :operator, :msisdn, :amount, :fee, :currency,
                       'PENDING', :provider, :settlement, :hold, :next, :created, :created)""",
        ).param("id", w.id).param("key", w.idempotencyKey).param("customer", w.customerId)
            .param("merchant", w.merchantId).param("by", requestedBy).param("operator", w.operator.name)
            .param("msisdn", w.msisdn).param("amount", w.amountMinor).param("fee", w.feeMinor).param("currency", w.currency.name)
            .param("provider", w.provider).param("settlement", w.settlementCode).param("hold", holdTransactionId)
            .param("next", nextCheckAt).param("created", w.createdAt).update()
    }

    fun findByKey(customerId: UUID, key: String): Withdrawal? =
        jdbc.sql("SELECT * FROM withdrawal WHERE customer_id = :c AND idempotency_key = :k")
            .param("c", customerId).param("k", key).query(::map).optional().orElse(null)

    fun findByMerchantKey(merchantId: UUID, key: String): Withdrawal? =
        jdbc.sql("SELECT * FROM withdrawal WHERE merchant_id = :m AND idempotency_key = :k")
            .param("m", merchantId).param("k", key).query(::map).optional().orElse(null)

    fun forMerchant(merchantId: UUID, limit: Int): List<Withdrawal> =
        jdbc.sql("SELECT * FROM withdrawal WHERE merchant_id = :m ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("m", merchantId).param("limit", limit).query(::map).list()

    fun findById(id: UUID): Withdrawal? =
        jdbc.sql("SELECT * FROM withdrawal WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    fun lock(id: UUID): Withdrawal? =
        jdbc.sql("SELECT * FROM withdrawal WHERE id = :id FOR UPDATE").param("id", id).query(::map).optional().orElse(null)

    fun dueForCheck(now: OffsetDateTime, limit: Int): List<UUID> =
        jdbc.sql("SELECT id FROM withdrawal WHERE status = 'PENDING' AND next_check_at <= :now ORDER BY next_check_at LIMIT :limit")
            .param("now", now).param("limit", limit).query(UUID::class.java).list()

    fun finish(id: UUID, status: TopupStatus, now: OffsetDateTime, transactionId: UUID? = null, reference: String? = null,
               failureCode: String? = null, failureMessage: String? = null, reviewReason: String? = null) {
        jdbc.sql(
            """UPDATE withdrawal SET status = :status,
                      final_transaction_id = COALESCE(:tx, final_transaction_id),
                      provider_reference = COALESCE(:ref, provider_reference),
                      failure_code = COALESCE(:code, failure_code), failure_message = COALESCE(:msg, failure_message),
                      review_reason = COALESCE(:reason, review_reason),
                      next_check_at = NULL, last_checked_at = :now, updated_at = :now
               WHERE id = :id""",
        ).param("status", status.name).param("tx", transactionId).param("ref", reference).param("code", failureCode)
            .param("msg", failureMessage?.take(300)).param("reason", reviewReason?.take(300)).param("now", now).param("id", id)
            .update()
    }

    fun reschedule(id: UUID, next: OffsetDateTime, now: OffsetDateTime) {
        jdbc.sql("UPDATE withdrawal SET checks = checks + 1, next_check_at = :next, last_checked_at = :now, updated_at = :now WHERE id = :id")
            .param("next", next).param("now", now).param("id", id).update()
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Withdrawal(
        id = rs.getObject("id", UUID::class.java),
        idempotencyKey = rs.getString("idempotency_key"),
        customerId = rs.getObject("customer_id", UUID::class.java),
        operator = Operator.valueOf(rs.getString("operator")),
        msisdn = rs.getString("msisdn"),
        amountMinor = rs.getLong("amount_minor"),
        feeMinor = rs.getLong("fee_minor"),
        currency = Currency.valueOf(rs.getString("currency")),
        status = TopupStatus.valueOf(rs.getString("status")),
        operatorReference = rs.getString("provider_reference"),
        failureCode = rs.getString("failure_code"),
        failureMessage = rs.getString("failure_message"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        provider = rs.getString("provider"),
        settlementCode = rs.getString("settlement_code"),
        checks = rs.getInt("checks"),
        reviewReason = rs.getString("review_reason"),
        merchantId = rs.getObject("merchant_id", UUID::class.java),
    )
}

/**
 * Retraits vers mobile money.
 *
 * 1. Demande : l'argent (montant + frais) quitte le portefeuille vers le compte d'attente PAYOUTS_IN_TRANSIT,
 *    sous verrou et après contrôle des plafonds. Il ne peut plus être dépensé ailleurs.
 * 2. Versement demandé au prestataire, hors transaction.
 * 3. Statut relu chez le prestataire (notification, relance, rapprochement) :
 *    payé → le montant passe sur le compte de règlement ; échec certain → tout revient au client.
 *    Un doute ne rembourse jamais : si l'opérateur payait quand même, le client serait payé deux fois.
 */
@Service
class WithdrawalService(
    private val withdrawals: WithdrawalRepository,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val router: MobileMoneyRouter,
    private val limits: LimitsService,
    private val fees: FeeService,
    private val properties: MobileMoneyProperties,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val notifications: NotificationService,
) {
    companion object {
        const val IN_TRANSIT = "PAYOUTS_IN_TRANSIT_USD"
        const val FEES = "FEES_REVENUE_USD"
    }

    private val log = LoggerFactory.getLogger(WithdrawalService::class.java)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun account(code: String) = ledgerRepository.findAccountByCode(code)?.id ?: error("Compte $code absent")

    fun initiate(idempotencyKey: String, customerId: UUID, operator: Operator, amountMinor: Long): Withdrawal {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val connector = router.forOperator(operator)
        val (withdrawal, created) = tx.execute {
            val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
            if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Client suspendu ou fermé")
            withdrawals.findByKey(customerId, idempotencyKey)?.let { return@execute replay(it, operator, amountMinor) to false }

            val wallet = ledgerRepository.findCustomerWallet(customerId, Currency.USD)!!
            ledgerRepository.lockAccounts(listOf(wallet.id))
            limits.checkOutgoing(customer, wallet.id, amountMinor)

            val fee = fees.withdrawalFee(amountMinor)
            val w = Withdrawal(
                id = UUID.randomUUID(), idempotencyKey = idempotencyKey, customerId = customerId, operator = operator,
                msisdn = customer.phone.removePrefix("+"), amountMinor = amountMinor, feeMinor = fee, currency = Currency.USD,
                status = TopupStatus.PENDING, operatorReference = null, failureCode = null, failureMessage = null, createdAt = now(),
                provider = connector.name, settlementCode = connector.settlementCode(operator), checks = 0, reviewReason = null,
            )
            hold(w, wallet.id, "WITHDRAWAL", "Retrait vers ${operator.name} +${w.msisdn}", null)
            w to true
        }!!
        return if (created) send(withdrawal) else withdrawal
    }

    /**
     * Reversement de ce que Capital Invest doit à un marchand, vers son compte mobile money.
     * Demandé par un agent ; sans frais (la commission prélevée sur les paiements les couvre).
     * L'appelant a vérifié le marchand et son compte de reversement.
     */
    fun initiateMerchantPayout(
        idempotencyKey: String, merchantId: UUID, walletId: UUID, operator: Operator, msisdn: String, amountMinor: Long, agentId: UUID,
    ): Withdrawal {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val connector = router.forOperator(operator)
        val (withdrawal, created) = tx.execute {
            withdrawals.findByMerchantKey(merchantId, idempotencyKey)?.let { return@execute replay(it, operator, amountMinor) to false }
            ledgerRepository.lockAccounts(listOf(walletId))
            val w = Withdrawal(
                id = UUID.randomUUID(), idempotencyKey = idempotencyKey, customerId = null, merchantId = merchantId, operator = operator,
                msisdn = msisdn, amountMinor = amountMinor, feeMinor = 0, currency = Currency.USD,
                status = TopupStatus.PENDING, operatorReference = null, failureCode = null, failureMessage = null, createdAt = now(),
                provider = connector.name, settlementCode = connector.settlementCode(operator), checks = 0, reviewReason = null,
            )
            hold(w, walletId, "MERCHANT_PAYOUT", "Reversement marchand vers ${operator.name} +$msisdn", agentId)
            w to true
        }!!
        return if (created) send(withdrawal) else withdrawal
    }

    /** Blocage des fonds. Le grand livre refuse si le solde ne couvre pas montant + frais. */
    private fun hold(w: Withdrawal, walletId: UUID, type: String, description: String, requestedBy: UUID?) {
        val hold = ledger.post(
            PostingRequest(
                idempotencyKey = "withdrawal:hold:${w.id}",
                type = type,
                description = description,
                lines = listOfNotNull(
                    PostingLine(walletId, Direction.DEBIT, w.amountMinor),
                    PostingLine(account(IN_TRANSIT), Direction.CREDIT, w.amountMinor),
                    if (w.feeMinor > 0) PostingLine(walletId, Direction.DEBIT, w.feeMinor) else null,
                    if (w.feeMinor > 0) PostingLine(account(FEES), Direction.CREDIT, w.feeMinor) else null,
                ),
            ),
        )
        withdrawals.insert(w, hold.transaction.id, w.createdAt.plusSeconds(properties.firstCheckSeconds), requestedBy)
    }

    /** Demande de versement au prestataire, hors transaction. Un refus immédiat rend l'argent. */
    private fun send(withdrawal: Withdrawal): Withdrawal {
        val result = try {
            router.byName(withdrawal.provider).initiatePayout(
                PaymentRequest(withdrawal.id, withdrawal.operator, withdrawal.msisdn, withdrawal.amountMinor, withdrawal.currency),
            )
        } catch (e: Exception) {
            log.warn("Demande de versement {} : {}", withdrawal.id, e.toString())
            InitiateResult.Unknown(e.javaClass.simpleName)
        }
        if (result is InitiateResult.Rejected) {
            tx.executeWithoutResult {
                val locked = withdrawals.lock(withdrawal.id)!!
                if (locked.status == TopupStatus.PENDING) refund(locked, result.code, result.message, now())
            }
        }
        return withdrawals.findById(withdrawal.id)!!
    }

    /** Portefeuille d'où vient l'argent : celui du client, ou le compte du marchand. */
    private fun walletOf(w: Withdrawal): UUID = when {
        w.customerId != null -> ledgerRepository.findCustomerWallet(w.customerId, w.currency)!!.id
        else -> jdbc.sql("SELECT wallet_account_id FROM merchant WHERE id = :m").param("m", w.merchantId)
            .query(UUID::class.java).single()
    }

    private fun ledgerType(w: Withdrawal, customerType: String, merchantType: String) =
        if (w.merchantId != null) merchantType else customerType

    private fun replay(existing: Withdrawal, operator: Operator, amountMinor: Long): Withdrawal {
        if (existing.operator != operator || existing.amountMinor != amountMinor) {
            throw ConflictException("IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour un autre retrait")
        }
        return existing
    }

    fun check(w: Withdrawal): CheckResult = try {
        router.byName(w.provider).checkPayout(w.id)
    } catch (e: Exception) {
        log.warn("Lecture du versement {} : {}", w.id, e.toString())
        CheckResult.Uncertain(e.javaClass.simpleName)
    }

    /** Relit le statut chez le prestataire et l'applique ; seul chemin vers un remboursement automatique. */
    fun refresh(id: UUID): Withdrawal {
        val w = get(id)
        if (w.status != TopupStatus.PENDING) return w
        val result = check(w)
        return tx.execute { apply(id, result) }!!
    }

    private fun apply(id: UUID, result: CheckResult): Withdrawal {
        val w = withdrawals.lock(id) ?: throw NotFoundException("WITHDRAWAL_NOT_FOUND", "Retrait introuvable")
        if (w.status != TopupStatus.PENDING) return w
        val now = now()
        val age = Duration.between(w.createdAt, now)
        when (result) {
            is CheckResult.Completed ->
                if (result.amountMinor == w.amountMinor && result.currency == w.currency.name) {
                    settle(w, w.amountMinor, result.reference, now)
                } else {
                    withdrawals.finish(
                        id, TopupStatus.REVIEW, now,
                        reviewReason = "Montant versé par l'opérateur : ${money(result.amountMinor, result.currency)}, demandé ${money(w.amountMinor, w.currency.name)}",
                    )
                }
            is CheckResult.Failed -> refund(w, result.code, result.message, now)
            CheckResult.NotFound ->
                if (age >= Duration.ofMinutes(properties.notFoundTimeoutMinutes)) {
                    refund(w, "NOT_RECEIVED", "L'opérateur n'a jamais reçu la demande de versement", now)
                } else {
                    reschedule(w, now)
                }
            CheckResult.Pending, is CheckResult.Uncertain ->
                if (age >= Duration.ofHours(properties.reviewAfterHours)) {
                    val detail = (result as? CheckResult.Uncertain)?.reason ?: "toujours en cours chez l'opérateur"
                    withdrawals.finish(id, TopupStatus.REVIEW, now, reviewReason = "Aucune réponse définitive après ${properties.reviewAfterHours} h ($detail)")
                } else {
                    reschedule(w, now)
                }
        }
        return withdrawals.findById(id)!!
    }

    private fun reschedule(w: Withdrawal, now: OffsetDateTime) {
        val delay = (properties.firstCheckSeconds shl w.checks.coerceAtMost(20)).coerceAtMost(properties.maxCheckIntervalSeconds)
        withdrawals.reschedule(w.id, now.plusSeconds(delay), now)
    }

    /** Versement confirmé : l'argent quitte le compte d'attente vers le compte de règlement ; un éventuel reste revient au client. */
    private fun settle(w: Withdrawal, paidMinor: Long, reference: String?, now: OffsetDateTime) {
        require(paidMinor in 1..w.amountMinor)
        val wallet = walletOf(w)
        val result = ledger.post(
            PostingRequest(
                idempotencyKey = "withdrawal:settle:${w.id}",
                type = ledgerType(w, "WITHDRAWAL_SETTLED", "MERCHANT_PAYOUT_SETTLED"),
                description = "Retrait versé par ${w.operator.name} ${reference ?: ""}".trim(),
                lines = listOfNotNull(
                    PostingLine(account(IN_TRANSIT), Direction.DEBIT, w.amountMinor),
                    PostingLine(account(w.settlementCode), Direction.CREDIT, paidMinor),
                    if (paidMinor < w.amountMinor) PostingLine(wallet, Direction.CREDIT, w.amountMinor - paidMinor) else null,
                ),
            ),
        )
        withdrawals.finish(w.id, TopupStatus.CONFIRMED, now, transactionId = result.transaction.id, reference = reference)
        if (w.customerId != null) {
            notifications.withdrawalPaid(w.id, w.customerId, w.operator, w.msisdn, paidMinor, result.transaction.id)
        } else {
            notifications.merchantPayout(w.id, w.merchantId!!, w.operator, w.msisdn, paidMinor, paid = true, transactionId = result.transaction.id)
        }
    }

    /** Échec certain : montant et frais reviennent au client. */
    private fun refund(w: Withdrawal, code: String, message: String, now: OffsetDateTime) {
        val wallet = walletOf(w)
        val result = ledger.post(
            PostingRequest(
                idempotencyKey = "withdrawal:refund:${w.id}",
                type = ledgerType(w, "WITHDRAWAL_REFUND", "MERCHANT_PAYOUT_REFUND"),
                description = "Retrait non versé, remboursé",
                lines = listOfNotNull(
                    PostingLine(account(IN_TRANSIT), Direction.DEBIT, w.amountMinor),
                    PostingLine(wallet, Direction.CREDIT, w.amountMinor),
                    if (w.feeMinor > 0) PostingLine(account(FEES), Direction.DEBIT, w.feeMinor) else null,
                    if (w.feeMinor > 0) PostingLine(wallet, Direction.CREDIT, w.feeMinor) else null,
                ),
            ),
        )
        withdrawals.finish(w.id, TopupStatus.FAILED, now, transactionId = result.transaction.id, failureCode = code, failureMessage = message)
        if (w.merchantId != null) {
            notifications.merchantPayout(w.id, w.merchantId, w.operator, w.msisdn, w.amountMinor, paid = false, transactionId = result.transaction.id)
        }
    }

    /** Annule un retrait noté versé alors que l'opérateur n'a rien payé : l'argent revient au client. */
    private fun reverse(w: Withdrawal, note: String, now: OffsetDateTime) {
        val wallet = walletOf(w)
        val result = ledger.post(
            PostingRequest(
                idempotencyKey = "withdrawal:reverse:${w.id}",
                type = ledgerType(w, "WITHDRAWAL_REFUND", "MERCHANT_PAYOUT_REFUND"),
                description = "Retrait non versé par l'opérateur, remboursé",
                lines = listOfNotNull(
                    PostingLine(account(w.settlementCode), Direction.DEBIT, w.amountMinor),
                    PostingLine(wallet, Direction.CREDIT, w.amountMinor),
                    if (w.feeMinor > 0) PostingLine(account(FEES), Direction.DEBIT, w.feeMinor) else null,
                    if (w.feeMinor > 0) PostingLine(wallet, Direction.CREDIT, w.feeMinor) else null,
                ),
            ),
        )
        withdrawals.finish(w.id, TopupStatus.FAILED, now, transactionId = result.transaction.id, failureCode = "REVERSED_BY_AGENT", failureMessage = note)
    }

    fun checkDue(limit: Int = 50): Int {
        val due = withdrawals.dueForCheck(now(), limit)
        due.forEach { id -> runCatching { refresh(id) }.onFailure { log.warn("Relance retrait {} : {}", id, it.toString()) } }
        return due.size
    }

    enum class Decision { PAID, REFUND }

    /**
     * Décision d'un agent, toujours après une nouvelle lecture chez le prestataire :
     * PAID exige que l'opérateur confirme le versement ; REFUND exige qu'il confirme l'échec.
     * Retrait à vérifier (REVIEW) : les deux. Retrait noté versé (CONFIRMED) : REFUND seulement.
     */
    fun resolve(id: UUID, decision: Decision, note: String): Withdrawal {
        val w = get(id)
        if (w.status != TopupStatus.REVIEW && !(w.status == TopupStatus.CONFIRMED && decision == Decision.REFUND)) {
            throw ConflictException("WITHDRAWAL_NOT_RESOLVABLE", "Retrait à l'état ${w.status}")
        }
        val result = check(w)
        return tx.execute {
            val locked = withdrawals.lock(id)!!
            if (locked.status != w.status) throw ConflictException("WITHDRAWAL_CHANGED", "Le retrait vient de changer d'état, rechargez la page")
            val now = now()
            when (decision) {
                Decision.PAID -> {
                    if (result !is CheckResult.Completed || result.currency != locked.currency.name) {
                        throw BusinessException("PROVIDER_NOT_COMPLETED", "L'opérateur ne confirme pas le versement (${label(result)}).")
                    }
                    if (result.amountMinor > locked.amountMinor) {
                        throw BusinessException("PAID_MORE_THAN_HELD", "L'opérateur a versé plus que le montant demandé : à régler avec la finance.")
                    }
                    settle(locked, result.amountMinor, result.reference, now)
                }
                Decision.REFUND -> {
                    if (result !is CheckResult.Failed && result != CheckResult.NotFound) {
                        throw BusinessException(
                            "PROVIDER_NOT_FAILED",
                            "L'opérateur ne confirme pas l'échec (${label(result)}) : rembourser risquerait de payer le client deux fois.",
                        )
                    }
                    if (locked.status == TopupStatus.CONFIRMED) reverse(locked, note, now)
                    else refund(locked, "REFUNDED_BY_AGENT", note, now)
                }
            }
            withdrawals.findById(id)!!
        }!!
    }

    fun get(id: UUID): Withdrawal = withdrawals.findById(id) ?: throw NotFoundException("WITHDRAWAL_NOT_FOUND", "Retrait introuvable")
}
