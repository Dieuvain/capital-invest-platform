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

enum class Operator(val settlementAccountCode: String) {
    MPESA("SETTLEMENT_MPESA_USD"),
    AIRTEL("SETTLEMENT_AIRTEL_USD"),
    ORANGE("SETTLEMENT_ORANGE_USD"),
}

/** REVIEW : un agent doit trancher (montant différent, ou aucune réponse sûre après 24 h). */
enum class TopupStatus { PENDING, CONFIRMED, FAILED, REVIEW }

data class Topup(
    val id: UUID,
    @JsonIgnore val idempotencyKey: String,
    @JsonIgnore val customerId: UUID,
    val operator: Operator,
    val amountMinor: Long,
    val currency: Currency,
    val status: TopupStatus,
    val operatorReference: String?,
    @JsonIgnore val ledgerTransactionId: UUID?,
    @JsonIgnore val provider: String = "SIMULATOR",
    @JsonIgnore val msisdn: String? = null,
    @JsonIgnore val settlementCode: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    @JsonIgnore val checks: Int = 0,
    @JsonIgnore val reviewReason: String? = null,
    val createdAt: OffsetDateTime? = null,
)

@Repository
class TopupRepository(private val jdbc: JdbcClient) {
    fun insertIfAbsent(t: Topup, nextCheckAt: OffsetDateTime): Boolean =
        jdbc.sql(
            """INSERT INTO topup (id, idempotency_key, customer_id, operator, amount_minor, currency, status,
                                  provider, msisdn, settlement_code, next_check_at, created_at, updated_at)
               VALUES (:id, :key, :customer, :operator, :amount, :currency, :status,
                       :provider, :msisdn, :settlement, :next, :created, :created)
               ON CONFLICT (idempotency_key) DO NOTHING""",
        )
            .param("id", t.id).param("key", t.idempotencyKey).param("customer", t.customerId)
            .param("operator", t.operator.name).param("amount", t.amountMinor)
            .param("currency", t.currency.name).param("status", t.status.name)
            .param("provider", t.provider).param("msisdn", t.msisdn).param("settlement", t.settlementCode)
            .param("next", nextCheckAt).param("created", t.createdAt)
            .update() == 1

    fun findByKey(key: String): Topup? =
        jdbc.sql("SELECT * FROM topup WHERE idempotency_key = :k").param("k", key).query(::map).optional().orElse(null)

    fun findById(id: UUID): Topup? =
        jdbc.sql("SELECT * FROM topup WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    fun lock(id: UUID): Topup? =
        jdbc.sql("SELECT * FROM topup WHERE id = :id FOR UPDATE").param("id", id).query(::map).optional().orElse(null)

    fun dueForCheck(now: OffsetDateTime, limit: Int): List<UUID> =
        jdbc.sql("SELECT id FROM topup WHERE status = 'PENDING' AND next_check_at <= :now ORDER BY next_check_at LIMIT :limit")
            .param("now", now).param("limit", limit).query(UUID::class.java).list()

    fun markConfirmed(id: UUID, reference: String?, ledgerTransactionId: UUID, amountMinor: Long, now: OffsetDateTime) {
        jdbc.sql(
            """UPDATE topup SET status = 'CONFIRMED', provider_reference = :ref, operator_reference = :ref,
                      ledger_transaction_id = :tx, amount_minor = :amount, next_check_at = NULL, last_checked_at = :now, updated_at = :now
               WHERE id = :id""",
        ).param("ref", reference).param("tx", ledgerTransactionId).param("amount", amountMinor)
            .param("now", now).param("id", id).update()
    }

    fun markFailed(id: UUID, code: String, message: String, now: OffsetDateTime) {
        jdbc.sql(
            """UPDATE topup SET status = 'FAILED', failure_code = :code, failure_message = :msg,
                      next_check_at = NULL, last_checked_at = :now, updated_at = :now
               WHERE id = :id""",
        ).param("code", code).param("msg", message.take(300)).param("now", now).param("id", id).update()
    }

    fun markReview(id: UUID, reason: String, now: OffsetDateTime) {
        jdbc.sql(
            """UPDATE topup SET status = 'REVIEW', review_reason = :reason, next_check_at = NULL, last_checked_at = :now, updated_at = :now
               WHERE id = :id""",
        ).param("reason", reason.take(300)).param("now", now).param("id", id).update()
    }

    fun reschedule(id: UUID, next: OffsetDateTime, now: OffsetDateTime) {
        jdbc.sql("UPDATE topup SET checks = checks + 1, next_check_at = :next, last_checked_at = :now, updated_at = :now WHERE id = :id")
            .param("next", next).param("now", now).param("id", id).update()
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
        provider = rs.getString("provider"),
        msisdn = rs.getString("msisdn"),
        settlementCode = rs.getString("settlement_code"),
        failureCode = rs.getString("failure_code"),
        failureMessage = rs.getString("failure_message"),
        checks = rs.getInt("checks"),
        reviewReason = rs.getString("review_reason"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )
}

@Service
class TopupService(
    private val topups: TopupRepository,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val router: MobileMoneyRouter,
    private val limits: LimitsService,
    private val properties: MobileMoneyProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(TopupService::class.java)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /**
     * Crée la recharge à l'état PENDING puis envoie la demande au prestataire de l'opérateur.
     * Rejouer la même clé renvoie la même recharge, sans nouvelle demande.
     * L'appel au prestataire se fait hors transaction : une réponse lente ne bloque pas le portefeuille.
     */
    fun initiate(idempotencyKey: String, customerId: UUID, operator: Operator, amountMinor: Long): Topup {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val connector = router.forOperator(operator)
        val (topup, created) = tx.execute {
            val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
            if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Client suspendu ou fermé")

            topups.findByKey(idempotencyKey)?.let { return@execute replay(it, customerId, operator, amountMinor) to false }

            // Verrou du portefeuille : deux recharges simultanées ne peuvent pas dépasser le solde maximal ensemble.
            val wallet = ledgerRepository.findCustomerWallet(customerId, Currency.USD)!!
            ledgerRepository.lockAccounts(listOf(wallet.id))
            limits.checkTopup(customer, wallet.id, amountMinor)

            val now = now()
            val candidate = Topup(
                UUID.randomUUID(), idempotencyKey, customerId, operator, amountMinor, Currency.USD,
                TopupStatus.PENDING, null, null,
                provider = connector.name,
                msisdn = customer.phone.removePrefix("+"),
                settlementCode = connector.settlementCode(operator),
                createdAt = now,
            )
            if (topups.insertIfAbsent(candidate, now.plusSeconds(properties.firstCheckSeconds))) candidate to true
            else replay(topups.findByKey(idempotencyKey)!!, customerId, operator, amountMinor) to false
        }!!
        if (!created) return topup

        val result = try {
            connector.initiate(PaymentRequest(topup.id, operator, topup.msisdn!!, amountMinor, topup.currency))
        } catch (e: Exception) {
            log.warn("Demande de paiement {} : {}", topup.id, e.toString())
            InitiateResult.Unknown(e.javaClass.simpleName)
        }
        if (result is InitiateResult.Rejected) {
            tx.executeWithoutResult {
                if (topups.lock(topup.id)?.status == TopupStatus.PENDING) topups.markFailed(topup.id, result.code, result.message, now())
            }
        }
        // Accepted ou Unknown : la recharge reste en attente, la relecture du statut tranchera.
        return topups.findById(topup.id)!!
    }

    private fun replay(existing: Topup, customerId: UUID, operator: Operator, amountMinor: Long): Topup {
        if (existing.customerId != customerId || existing.operator != operator || existing.amountMinor != amountMinor) {
            throw ConflictException("IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour une autre recharge")
        }
        return existing
    }

    /**
     * Relit le statut chez le prestataire et l'applique. C'est le seul chemin vers un crédit :
     * webhook, relance automatique et rapprochement passent tous par ici.
     */
    fun refresh(topupId: UUID): Topup {
        val topup = topups.findById(topupId) ?: throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
        if (topup.status != TopupStatus.PENDING) return topup
        val result = check(topup)
        return tx.execute { apply(topupId, result) }!!
    }

    fun check(topup: Topup): CheckResult = try {
        router.byName(topup.provider).check(topup)
    } catch (e: Exception) {
        log.warn("Lecture du statut {} : {}", topup.id, e.toString())
        CheckResult.Uncertain(e.javaClass.simpleName)
    }

    /** Applique un statut lu chez le prestataire, sous verrou. Une recharge déjà finale ne bouge plus. */
    private fun apply(topupId: UUID, result: CheckResult): Topup {
        val topup = topups.lock(topupId) ?: throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
        if (topup.status != TopupStatus.PENDING) return topup
        val now = now()
        val age = Duration.between(topup.createdAt ?: now, now)
        when (result) {
            is CheckResult.Completed ->
                if (result.amountMinor == topup.amountMinor && result.currency == topup.currency.name) {
                    credit(topup, result.amountMinor, result.reference, now)
                } else {
                    topups.markReview(
                        topupId,
                        "Montant reçu chez l'opérateur : ${money(result.amountMinor, result.currency)}, attendu ${money(topup.amountMinor, topup.currency.name)}",
                        now,
                    )
                }
            is CheckResult.Failed -> topups.markFailed(topupId, result.code, result.message, now)
            CheckResult.NotFound ->
                if (age >= Duration.ofMinutes(properties.notFoundTimeoutMinutes)) {
                    topups.markFailed(topupId, "NOT_RECEIVED", "L'opérateur n'a jamais reçu la demande de paiement", now)
                } else {
                    reschedule(topup, now)
                }
            CheckResult.Pending, is CheckResult.Uncertain ->
                if (age >= Duration.ofHours(properties.reviewAfterHours)) {
                    val detail = (result as? CheckResult.Uncertain)?.reason ?: "toujours en cours chez l'opérateur"
                    topups.markReview(topupId, "Aucune réponse définitive après ${properties.reviewAfterHours} h ($detail)", now)
                } else {
                    reschedule(topup, now)
                }
        }
        return topups.findById(topupId)!!
    }

    private fun reschedule(topup: Topup, now: OffsetDateTime) {
        val delay = (properties.firstCheckSeconds shl topup.checks.coerceAtMost(20)).coerceAtMost(properties.maxCheckIntervalSeconds)
        topups.reschedule(topup.id, now.plusSeconds(delay), now)
    }

    private fun credit(topup: Topup, amountMinor: Long, reference: String?, now: OffsetDateTime) {
        val code = topup.settlementCode ?: topup.operator.settlementAccountCode
        val settlement = ledgerRepository.findAccountByCode(code) ?: error("Compte de règlement absent : $code")
        val wallet = ledgerRepository.findCustomerWallet(topup.customerId, topup.currency)
            ?: error("Portefeuille absent pour ${topup.customerId}")
        val result = ledger.post(
            PostingRequest(
                idempotencyKey = "topup:${topup.id}",
                type = "TOPUP",
                description = "Recharge ${topup.operator} ${reference ?: ""}".trim(),
                lines = listOf(
                    PostingLine(settlement.id, Direction.DEBIT, amountMinor),
                    PostingLine(wallet.id, Direction.CREDIT, amountMinor),
                ),
            ),
        )
        topups.markConfirmed(topup.id, reference, result.transaction.id, amountMinor, now)
    }

    /** Recharges dont la prochaine vérification est due. Appelé par la relance automatique. */
    fun checkDue(limit: Int = 50): Int {
        val due = topups.dueForCheck(now(), limit)
        due.forEach { id -> runCatching { refresh(id) }.onFailure { log.warn("Relance {} : {}", id, it.toString()) } }
        return due.size
    }

    /**
     * Décision d'un agent sur une recharge à vérifier, ou sur un écart du rapprochement.
     * Créditer exige que le prestataire confirme encore le paiement à l'instant : on crédite le montant
     * réellement encaissé. Clore en échec est refusé si le prestataire dit que l'argent est arrivé.
     */
    fun resolve(topupId: UUID, credit: Boolean, note: String, allowed: Set<TopupStatus>): Topup {
        val topup = topups.findById(topupId) ?: throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
        if (topup.status !in allowed) throw ConflictException("TOPUP_NOT_RESOLVABLE", "Recharge à l'état ${topup.status}")
        val result = check(topup)
        return tx.execute {
            val locked = topups.lock(topupId)!!
            if (locked.status != topup.status) throw ConflictException("TOPUP_CHANGED", "La recharge vient de changer d'état, rechargez la page")
            val now = now()
            if (credit) {
                if (result !is CheckResult.Completed) {
                    throw BusinessException("PROVIDER_NOT_COMPLETED", "L'opérateur ne confirme pas le paiement (${label(result)}). Rien n'a été crédité.")
                }
                if (result.currency != locked.currency.name) {
                    throw BusinessException("CURRENCY_MISMATCH", "Devise reçue ${result.currency}, attendue ${locked.currency}")
                }
                credit(locked, result.amountMinor, result.reference, now)
            } else {
                if (result is CheckResult.Completed) {
                    throw BusinessException("PROVIDER_COMPLETED", "L'opérateur confirme le paiement : créditez plutôt le client.")
                }
                topups.markFailed(topupId, "CLOSED_BY_AGENT", note, now)
            }
            topups.findById(topupId)!!
        }!!
    }

    fun get(id: UUID): Topup = topups.findById(id) ?: throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
}

/** 4000, USD → « 40,00 $ » ; autre devise → « 40,00 CDF ». */
private fun money(minor: Long, currency: String): String =
    java.math.BigDecimal.valueOf(minor, 2).toPlainString().replace('.', ',') + if (currency == "USD") " $" else " $currency"

/** Libellé court d'un statut lu chez le prestataire, pour le back-office et le rapprochement. */
fun label(result: CheckResult): String = when (result) {
    is CheckResult.Completed -> "COMPLETED"
    is CheckResult.Failed -> "FAILED"
    CheckResult.NotFound -> "NOT_FOUND"
    CheckResult.Pending -> "PENDING"
    is CheckResult.Uncertain -> "UNKNOWN"
}
