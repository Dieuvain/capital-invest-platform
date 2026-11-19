package cd.capitalinvest.platform.merchants

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.ledger.AccountStatus
import cd.capitalinvest.platform.ledger.AccountType
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerAccount
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.notifications.NotificationService
import cd.capitalinvest.platform.notifications.NotificationService.Companion.money
import cd.capitalinvest.platform.payments.Operator
import cd.capitalinvest.platform.payments.TopupStatus
import cd.capitalinvest.platform.payments.Withdrawal
import cd.capitalinvest.platform.payments.WithdrawalRepository
import cd.capitalinvest.platform.payments.WithdrawalService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.merchants")
data class MerchantProperties(
    /** Commission proposée à la création d'un marchand, en points de base (100 = 1 %). PROVISOIRE. */
    val defaultCommissionBps: Int = 100,
    /** Après un changement du compte de reversement, les reversements attendent ce délai (vol de compte). */
    val payoutAccountCooldownHours: Long = 24,
    /** Plus petit reversement, en centimes. */
    val minPayoutMinor: Long = 100,
    /** Reversement automatique chaque nuit (voir `auto-payout-cron`). Coupé dans les tests, qui le lancent eux-mêmes. */
    val autoPayoutEnabled: Boolean = true,
    /** En dessous de ce solde, la nuit ne reverse pas : on attend que le montant vaille l'opération. 10,00 $ par défaut. */
    val autoPayoutMinMinor: Long = 1_000,
)

enum class MerchantStatus { ACTIVE, SUSPENDED }

data class Merchant(
    val id: UUID,
    val code: String,
    val name: String,
    val category: String?,
    val city: String?,
    val phone: String,
    val commissionBps: Int,
    val status: MerchantStatus,
    val walletAccountId: UUID,
    val createdAt: OffsetDateTime,
    val payoutOperator: Operator? = null,
    /** Numéro mobile money des reversements, au format international sans « + ». */
    val payoutMsisdn: String? = null,
    val payoutAccountChangedAt: OffsetDateTime? = null,
    /** Reversement automatique chaque nuit. */
    val autoPayout: Boolean = true,
)

/** Ce que l'app montre avant de payer : de quoi reconnaître le commerce, rien de plus. */
data class MerchantPublicView(val code: String, val name: String, val category: String?, val city: String?)

data class MerchantPaymentResult(
    val transactionId: UUID,
    val receiptNumber: String,
    val merchantCode: String,
    val merchantName: String,
    val amountMinor: Long,
    val currency: Currency,
    val createdAt: OffsetDateTime,
    val replayed: Boolean,
)

/** Fiche marchand du back-office. */
data class MerchantView(
    val id: UUID,
    val code: String,
    val name: String,
    val category: String?,
    val city: String?,
    val phone: String,
    val commissionBps: Int,
    val status: MerchantStatus,
    /** Ce que Capital Invest doit au marchand : paiements reçus, commission déduite. */
    val balanceMinor: Long,
    /** Contenu du QR à imprimer pour la caisse. */
    val qrPayload: String,
    val createdAt: OffsetDateTime,
    /** Compte mobile money qui reçoit les reversements ; null tant qu'il n'est pas renseigné. */
    val payoutOperator: Operator?,
    val payoutPhone: String?,
    /** Reversements suspendus jusqu'à cette heure, après un changement du compte. */
    val payoutBlockedUntil: OffsetDateTime?,
    /** Ce qui est dû part tout seul chaque nuit vers le compte de reversement. */
    val autoPayout: Boolean,
)

/** Un reversement au marchand, avec son état chez l'opérateur. */
data class MerchantPayoutLine(
    val id: UUID,
    val amountMinor: Long,
    val operator: Operator,
    val phone: String,
    /** PENDING : en cours ; CONFIRMED : versé ; FAILED : refusé, l'argent est revenu ; REVIEW : à vérifier. */
    val status: TopupStatus,
    val failureMessage: String?,
    val reviewReason: String?,
    val createdAt: OffsetDateTime,
    /** Parti avec la tâche de nuit, sans demande d'un agent. */
    val automatic: Boolean,
)

data class MerchantPaymentLine(
    val transactionId: UUID,
    val receiptNumber: String,
    val customerName: String,
    val customerPhone: String,
    val amountMinor: Long,
    val commissionMinor: Long,
    val createdAt: OffsetDateTime,
)

/** Bilan d'une nuit de reversements automatiques. */
data class AutoPayoutReport(val date: LocalDate, val sent: Int, val skipped: Int, val failed: Int)

data class MerchantDetail(val merchant: MerchantView, val payments: List<MerchantPaymentLine>, val payouts: List<MerchantPayoutLine>)

/**
 * Paiement chez un marchand : le client scanne le QR en caisse (ou tape le code à 6 caractères),
 * vérifie le nom du commerce, tape le montant et confirme par son PIN. Il ne paie pas de frais ;
 * le marchand reçoit le montant moins sa commission et un SMS de confirmation.
 */
@Service
class MerchantService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val history: HistoryService,
    private val props: MerchantProperties,
    private val audit: AuditLog,
    private val clock: Clock,
    private val withdrawals: WithdrawalService,
    private val withdrawalRepository: WithdrawalRepository,
    private val notifications: NotificationService,
) {
    private val random = SecureRandom()
    private val log = LoggerFactory.getLogger(MerchantService::class.java)
    private val feesAccountId by lazy {
        ledgerRepository.findAccountByCode("FEES_REVENUE_USD")?.id ?: error("Compte FEES_REVENUE_USD absent")
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    fun findByCode(code: String): Merchant? =
        jdbc.sql("SELECT * FROM merchant WHERE code = :c").param("c", normalizeCode(code)).query(::map).optional().orElse(null)

    fun get(id: UUID): Merchant =
        jdbc.sql("SELECT * FROM merchant WHERE id = :id").param("id", id).query(::map).optional().orElse(null)
            ?: throw NotFoundException("MERCHANT_NOT_FOUND", "Marchand introuvable")

    /** Le commerce derrière un code scanné ou tapé. Un marchand suspendu ne peut pas être payé. */
    fun lookup(code: String): MerchantPublicView {
        val m = findByCode(code) ?: throw NotFoundException("MERCHANT_NOT_FOUND", "Aucun marchand avec ce code")
        if (m.status != MerchantStatus.ACTIVE) throw BusinessException("MERCHANT_NOT_ACTIVE", "Ce marchand n'accepte pas de paiement pour le moment")
        return MerchantPublicView(m.code, m.name, m.category, m.city)
    }

    /** Commission arrondie au centime le plus proche (demi vers le haut). */
    fun commission(amountMinor: Long, bps: Int): Long = (amountMinor * bps + 5_000) / 10_000

    @Transactional
    fun pay(idempotencyKey: String, customerId: UUID, merchantCode: String, amountMinor: Long, note: String?): MerchantPaymentResult {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val merchant = findByCode(merchantCode) ?: throw NotFoundException("MERCHANT_NOT_FOUND", "Aucun marchand avec ce code")
        val ledgerKey = "merchant-payment:${customer.id}:$idempotencyKey"
        val existing = ledgerRepository.findTransactionHeaderByKey(ledgerKey)
        if (existing == null) {
            if (merchant.status != MerchantStatus.ACTIVE) {
                throw BusinessException("MERCHANT_NOT_ACTIVE", "Ce marchand n'accepte pas de paiement pour le moment")
            }
            if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu")
        }
        val wallet = ledgerRepository.findCustomerWallet(customer.id, Currency.USD)!!
        if (existing == null) {
            // Verrou du portefeuille avant les plafonds : deux paiements simultanés ne passent pas ensemble.
            ledgerRepository.lockAccounts(listOf(wallet.id))
            limits.checkOutgoing(customer, wallet.id, amountMinor)
        }
        val commission = commission(amountMinor, merchant.commissionBps)
        val result = ledger.post(
            PostingRequest(
                idempotencyKey = ledgerKey,
                type = "MERCHANT_PAYMENT",
                description = note?.trim()?.take(140)?.ifEmpty { null },
                lines = buildList {
                    add(PostingLine(wallet.id, Direction.DEBIT, amountMinor))
                    add(PostingLine(merchant.walletAccountId, Direction.CREDIT, amountMinor - commission))
                    if (commission > 0) add(PostingLine(feesAccountId, Direction.CREDIT, commission))
                },
            ),
        )
        val tx = result.transaction
        if (!result.replayed) {
            jdbc.sql(
                """INSERT INTO merchant_payment (id, merchant_id, customer_id, amount_minor, commission_minor, currency, note, transaction_id, created_at)
                   VALUES (:id, :m, :c, :amount, :commission, :cur, :note, :tx, :at)""",
            ).param("id", UUID.randomUUID()).param("m", merchant.id).param("c", customer.id).param("amount", amountMinor)
                .param("commission", commission).param("cur", Currency.USD.name).param("note", tx.description)
                .param("tx", tx.id).param("at", tx.createdAt).update()
        }
        return MerchantPaymentResult(
            tx.id, history.receiptNumber(tx.id, tx.createdAt), merchant.code, merchant.name, amountMinor, Currency.USD,
            tx.createdAt, result.replayed,
        )
    }

    // --- Back-office ---

    @Transactional
    fun create(
        agentId: UUID, name: String, category: String?, city: String?, phone: String, commissionBps: Int?,
        payoutOperator: Operator? = null, payoutPhone: String? = null,
    ): MerchantView {
        val normalized = normalizeDrcPhone(phone) ?: throw BusinessException("INVALID_PHONE", "Numéro du marchand invalide (+243…)")
        if ((payoutOperator == null) != (payoutPhone.isNullOrBlank())) {
            throw BusinessException("INVALID_PAYOUT_ACCOUNT", "Indiquez l'opérateur et le numéro du compte de reversement, ou aucun des deux")
        }
        val payoutMsisdn = payoutPhone?.takeIf { it.isNotBlank() }?.let {
            normalizeDrcPhone(it)?.removePrefix("+") ?: throw BusinessException("INVALID_PAYOUT_ACCOUNT", "Numéro de reversement invalide (+243…)")
        }
        val bps = commissionBps ?: props.defaultCommissionBps
        if (bps !in 0..1000) throw BusinessException("INVALID_COMMISSION", "La commission doit être entre 0 et 10 %")
        val wallet = LedgerAccount(
            id = UUID.randomUUID(), code = null, type = AccountType.MERCHANT_WALLET, currency = Currency.USD,
            normalSide = Direction.CREDIT, // dette de Capital Invest envers le marchand
            allowNegative = false, customerId = null, status = AccountStatus.ACTIVE,
        )
        ledgerRepository.createAccount(wallet)
        val id = UUID.randomUUID()
        val now = now()
        val code = generateSequence { newCode() }.first { findByCode(it) == null }
        jdbc.sql(
            """INSERT INTO merchant (id, code, name, category, city, phone, commission_bps, status, wallet_account_id, created_by,
                                    payout_operator, payout_msisdn, created_at, updated_at)
               VALUES (:id, :code, :name, :cat, :city, :phone, :bps, 'ACTIVE', :wallet, :by, :pop, :pmsisdn, :now, :now)""",
        ).param("id", id).param("code", code).param("name", name.trim()).param("cat", category?.trim()?.ifEmpty { null })
            .param("city", city?.trim()?.ifEmpty { null }).param("phone", normalized).param("bps", bps)
            .param("wallet", wallet.id).param("by", agentId).param("pop", payoutOperator?.name).param("pmsisdn", payoutMsisdn)
            .param("now", now).update()
        audit.record(ActorType.AGENT, agentId, "MERCHANT_CREATED", "MERCHANT", id, "$code ${name.trim()}, commission $bps pb")
        return view(get(id))
    }

    @Transactional
    fun setStatus(agentId: UUID, id: UUID, status: MerchantStatus, reason: String?): MerchantView {
        val m = get(id)
        if (m.status != status) {
            jdbc.sql("UPDATE merchant SET status = :s, updated_at = :now WHERE id = :id")
                .param("s", status.name).param("now", now()).param("id", id).update()
            audit.record(
                ActorType.AGENT, agentId, if (status == MerchantStatus.ACTIVE) "MERCHANT_REACTIVATED" else "MERCHANT_SUSPENDED",
                "MERCHANT", id, reason,
            )
        }
        return view(get(id))
    }

    /**
     * Change le compte qui reçoit les reversements. C'est la cible d'un fraudeur : le marchand est prévenu
     * par SMS sur son numéro habituel, et aucun reversement ne part pendant le délai de sécurité.
     */
    @Transactional
    fun setPayoutAccount(agentId: UUID, id: UUID, operator: Operator, phone: String): MerchantView {
        val m = get(id)
        val msisdn = normalizeDrcPhone(phone)?.removePrefix("+")
            ?: throw BusinessException("INVALID_PAYOUT_ACCOUNT", "Numéro de reversement invalide (+243…)")
        if (m.payoutOperator == operator && m.payoutMsisdn == msisdn) return view(m)
        val now = now()
        // Premier compte renseigné : pas de délai, rien n'a encore été versé ailleurs.
        val changedAt = if (m.payoutMsisdn == null) null else now
        jdbc.sql("UPDATE merchant SET payout_operator = :op, payout_msisdn = :msisdn, payout_account_changed_at = :at, updated_at = :now WHERE id = :id")
            .param("op", operator.name).param("msisdn", msisdn).param("at", changedAt).param("now", now).param("id", id).update()
        audit.record(
            ActorType.AGENT, agentId, "MERCHANT_PAYOUT_ACCOUNT_CHANGED", "MERCHANT", id,
            "${m.payoutOperator?.name ?: "aucun"} ${m.payoutMsisdn?.let { "+$it" } ?: ""} → ${operator.name} +$msisdn".replace("  ", " "),
        )
        notifications.merchantPayoutAccountChanged(id, operator, msisdn)
        return view(get(id))
    }

    /**
     * Reverse au marchand ce que Capital Invest lui doit (tout, ou le montant donné), vers son compte de reversement.
     * Le même identifiant de demande rejoué ne reverse jamais deux fois.
     */
    fun payout(agentId: UUID?, id: UUID, amountMinor: Long?, requestId: String): MerchantPayoutLine {
        val m = get(id)
        val operator = m.payoutOperator
        val msisdn = m.payoutMsisdn
        if (operator == null || msisdn == null) {
            throw BusinessException("PAYOUT_ACCOUNT_MISSING", "Renseignez d'abord le compte de reversement du marchand")
        }
        payoutBlockedUntil(m)?.let {
            throw BusinessException(
                "PAYOUT_ACCOUNT_RECENTLY_CHANGED",
                "Compte de reversement changé récemment : par sécurité, pas de reversement avant ${props.payoutAccountCooldownHours} h",
            )
        }
        withdrawalRepository.findByMerchantKey(id, requestId)?.let { return line(it) }
        val balance = ledgerRepository.balance(m.walletAccountId)
        val amount = amountMinor ?: balance
        if (amount < props.minPayoutMinor) {
            throw BusinessException("PAYOUT_TOO_SMALL", "Reversement de ${money(props.minPayoutMinor)} au moins")
        }
        if (amount > balance) throw BusinessException("INSUFFICIENT_FUNDS", "Le marchand n'a que ${money(balance)} à reverser")
        val w = withdrawals.initiateMerchantPayout(requestId, m.id, m.walletAccountId, operator, msisdn, amount, agentId)
        audit.record(
            if (agentId == null) ActorType.SYSTEM else ActorType.AGENT, agentId,
            if (agentId == null) "MERCHANT_PAYOUT_AUTOMATIC" else "MERCHANT_PAYOUT_REQUESTED", "MERCHANT", id,
            "${money(amount)} vers ${operator.name} +$msisdn",
        )
        return line(w)
    }

    /** Active ou coupe le reversement automatique de nuit pour ce marchand. */
    @Transactional
    fun setAutoPayout(agentId: UUID, id: UUID, enabled: Boolean): MerchantView {
        val m = get(id)
        if (m.autoPayout != enabled) {
            jdbc.sql("UPDATE merchant SET auto_payout = :on, updated_at = :now WHERE id = :id")
                .param("on", enabled).param("now", now()).param("id", id).update()
            audit.record(ActorType.AGENT, agentId, if (enabled) "MERCHANT_AUTO_PAYOUT_ON" else "MERCHANT_AUTO_PAYOUT_OFF", "MERCHANT", id)
        }
        return view(get(id))
    }

    /**
     * Reversements de la nuit : chaque marchand actif, en reversement automatique, avec un compte de reversement
     * hors délai de sécurité et au moins [MerchantProperties.autoPayoutMinMinor] dû, reçoit tout son solde.
     * Un marchand qui a déjà un reversement en cours ou à vérifier attend la nuit suivante.
     * L'identifiant `auto:<date>` empêche un second reversement la même nuit, même si la tâche repasse.
     */
    fun runAutoPayouts(date: LocalDate): AutoPayoutReport {
        val candidates = jdbc.sql(
            """SELECT m.* FROM merchant m
               WHERE m.status = 'ACTIVE' AND m.auto_payout AND m.payout_msisdn IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM withdrawal w WHERE w.merchant_id = m.id AND w.status IN ('PENDING', 'REVIEW'))
               ORDER BY m.code""",
        ).query(::map).list()
        var sent = 0
        var skipped = 0
        var failed = 0
        for (m in candidates) {
            if (payoutBlockedUntil(m) != null || ledgerRepository.balance(m.walletAccountId) < props.autoPayoutMinMinor) {
                skipped++
                continue
            }
            runCatching { payout(null, m.id, null, "auto:$date") }
                .onSuccess { sent++ }
                .onFailure { failed++; log.error("Reversement automatique de {} : {}", m.code, it.toString()) }
        }
        return AutoPayoutReport(date, sent, skipped, failed)
    }

    private fun payoutBlockedUntil(m: Merchant): OffsetDateTime? {
        val until = m.payoutAccountChangedAt?.plusHours(props.payoutAccountCooldownHours) ?: return null
        return if (now().isBefore(until)) until else null
    }

    private fun line(w: Withdrawal) = MerchantPayoutLine(
        w.id, w.amountMinor, w.operator, "+${w.msisdn}", w.status, w.failureMessage, w.reviewReason, w.createdAt,
        automatic = w.requestedBy == null,
    )

    fun list(): List<MerchantView> =
        jdbc.sql("SELECT * FROM merchant ORDER BY name, code").query(::map).list().map(::view)

    fun detail(id: UUID, limit: Int = 50): MerchantDetail {
        val m = get(id)
        val payments = jdbc.sql(
            """SELECT p.transaction_id, p.amount_minor, p.commission_minor, p.created_at, c.full_name, c.phone
               FROM merchant_payment p JOIN customer c ON c.id = p.customer_id
               WHERE p.merchant_id = :m ORDER BY p.created_at DESC, p.id DESC LIMIT :limit""",
        ).param("m", id).param("limit", limit.coerceIn(1, 200)).query { rs, _ ->
            val tx = rs.getObject("transaction_id", UUID::class.java)
            val at = rs.getObject("created_at", OffsetDateTime::class.java)
            MerchantPaymentLine(
                tx, history.receiptNumber(tx, at), rs.getString("full_name"), rs.getString("phone"),
                rs.getLong("amount_minor"), rs.getLong("commission_minor"), at,
            )
        }.list()
        return MerchantDetail(view(m), payments, withdrawalRepository.forMerchant(id, 20).map(::line))
    }

    fun view(m: Merchant) = MerchantView(
        m.id, m.code, m.name, m.category, m.city, m.phone, m.commissionBps, m.status,
        ledgerRepository.balance(m.walletAccountId), qrPayload(m.code), m.createdAt,
        m.payoutOperator, m.payoutMsisdn?.let { "+$it" }, payoutBlockedUntil(m), m.autoPayout,
    )

    private fun newCode(): String = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Merchant(
        id = rs.getObject("id", UUID::class.java),
        code = rs.getString("code"),
        name = rs.getString("name"),
        category = rs.getString("category"),
        city = rs.getString("city"),
        phone = rs.getString("phone"),
        commissionBps = rs.getInt("commission_bps"),
        status = MerchantStatus.valueOf(rs.getString("status")),
        walletAccountId = rs.getObject("wallet_account_id", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        payoutOperator = rs.getString("payout_operator")?.let(Operator::valueOf),
        payoutMsisdn = rs.getString("payout_msisdn"),
        payoutAccountChangedAt = rs.getObject("payout_account_changed_at", OffsetDateTime::class.java),
        autoPayout = rs.getBoolean("auto_payout"),
    )

    companion object {
        /** Sans 0/O ni 1/I : le code se lit et se tape sans confusion. */
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Ce que contient le QR du marchand. L'app accepte aussi le code seul. */
        fun qrPayload(code: String) = "capitalinvest://pay?m=$code"

        /** « k7p 2qx », « capitalinvest://pay?m=K7P2QX » → « K7P2QX ». */
        fun normalizeCode(input: String): String {
            val raw = Regex("[?&]m=([A-Za-z0-9 -]+)").find(input)?.groupValues?.get(1) ?: input
            return raw.uppercase().filter { it.isLetterOrDigit() }
        }
    }
}

data class MerchantPaymentRequest(
    @field:NotBlank val merchantCode: String,
    @field:Positive val amountMinor: Long,
    @field:Size(max = 140) val note: String? = null,
    /** Chaque paiement se confirme par le PIN, comme un envoi. */
    val pin: String,
)

@RestController
@RequestMapping("/v1")
class MerchantPaymentController(private val merchants: MerchantService, private val auth: AuthService) {

    @GetMapping("/merchants/{code}")
    fun lookup(@PathVariable code: String): MerchantPublicView = merchants.lookup(code)

    @PostMapping("/merchant-payments")
    fun pay(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: MerchantPaymentRequest,
    ): ResponseEntity<MerchantPaymentResult> {
        auth.confirmWithPin(customerId, body.pin)
        val result = merchants.pay(key, customerId, body.merchantCode, body.amountMinor, body.note)
        return ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(result)
    }
}

data class CreateMerchantRequest(
    @field:NotBlank @field:Size(max = 80) val name: String,
    @field:Size(max = 60) val category: String? = null,
    @field:Size(max = 60) val city: String? = null,
    @field:NotBlank val phone: String,
    @field:Min(0) @field:Max(1000) val commissionBps: Int? = null,
    /** Compte mobile money qui reçoit les reversements (facultatif à la création). */
    val payoutOperator: Operator? = null,
    val payoutPhone: String? = null,
)

data class PayoutAccountRequest(val operator: Operator, @field:NotBlank val phone: String)

data class PayoutRequest(
    /** Montant en centimes ; absent : tout ce qui est dû. */
    @field:Positive val amountMinor: Long? = null,
    /** Identifiant unique de la demande, généré par l'écran : un double clic ne reverse pas deux fois. */
    @field:NotBlank @field:Size(max = 100) val requestId: String,
)

data class AutoPayoutRequest(val enabled: Boolean)

data class MerchantStatusRequest(val status: MerchantStatus, @field:Size(max = 300) val reason: String? = null)

/** Création et suivi des marchands. Les administrateurs créent et suspendent ; la conformité consulte. */
@RestController
@RequestMapping("/v1/backoffice/merchants")
class BackofficeMerchantController(private val merchants: MerchantService) {

    private fun requireRead(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    private fun requireAdmin(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN) throw ForbiddenException("ADMIN_ONLY", "Réservé aux administrateurs")
    }

    @GetMapping
    fun list(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): List<MerchantView> {
        requireRead(me)
        return merchants.list()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @Valid @RequestBody b: CreateMerchantRequest): MerchantView {
        requireAdmin(me)
        return merchants.create(me.id, b.name, b.category, b.city, b.phone, b.commissionBps, b.payoutOperator, b.payoutPhone)
    }

    @GetMapping("/{id}")
    fun detail(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): MerchantDetail {
        requireRead(me)
        return merchants.detail(id)
    }

    @PostMapping("/{id}/payout-account")
    fun payoutAccount(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: PayoutAccountRequest): MerchantView {
        requireAdmin(me)
        return merchants.setPayoutAccount(me.id, id, b.operator, b.phone)
    }

    @PostMapping("/{id}/payouts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun payout(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: PayoutRequest): MerchantPayoutLine {
        requireAdmin(me)
        return merchants.payout(me.id, id, b.amountMinor, b.requestId)
    }

    @PostMapping("/{id}/auto-payout")
    fun autoPayout(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @RequestBody b: AutoPayoutRequest): MerchantView {
        requireAdmin(me)
        return merchants.setAutoPayout(me.id, id, b.enabled)
    }

    @PostMapping("/{id}/status")
    fun status(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: MerchantStatusRequest): MerchantView {
        requireAdmin(me)
        return merchants.setStatus(me.id, id, b.status, b.reason)
    }
}

/** Chaque nuit à 1 h 30 (heure de Lubumbashi), avant le rapprochement de 2 h : reversements automatiques aux marchands. */
@Component
class MerchantJobs(
    private val merchants: MerchantService,
    private val props: MerchantProperties,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(MerchantJobs::class.java)

    @Scheduled(cron = "\${capital-invest.merchants.auto-payout-cron:0 30 1 * * *}", zone = "Africa/Lubumbashi")
    fun nightly() {
        if (!props.autoPayoutEnabled) return
        val today = LocalDate.now(clock.withZone(ZoneId.of(limits.timezone)))
        runCatching { merchants.runAutoPayouts(today) }
            .onSuccess { log.info("Reversements automatiques du {} : {} partis, {} en attente, {} en erreur", today, it.sent, it.skipped, it.failed) }
            .onFailure { log.error("Reversements automatiques du {} : {}", today, it.toString()) }
    }
}
