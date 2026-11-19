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
import cd.capitalinvest.platform.limits.LimitsService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.merchants")
data class MerchantProperties(
    /** Commission proposée à la création d'un marchand, en points de base (100 = 1 %). PROVISOIRE. */
    val defaultCommissionBps: Int = 100,
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

data class MerchantDetail(val merchant: MerchantView, val payments: List<MerchantPaymentLine>)

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
) {
    private val random = SecureRandom()
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
    fun create(agentId: UUID, name: String, category: String?, city: String?, phone: String, commissionBps: Int?): MerchantView {
        val normalized = normalizeDrcPhone(phone) ?: throw BusinessException("INVALID_PHONE", "Numéro du marchand invalide (+243…)")
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
            """INSERT INTO merchant (id, code, name, category, city, phone, commission_bps, status, wallet_account_id, created_by, created_at, updated_at)
               VALUES (:id, :code, :name, :cat, :city, :phone, :bps, 'ACTIVE', :wallet, :by, :now, :now)""",
        ).param("id", id).param("code", code).param("name", name.trim()).param("cat", category?.trim()?.ifEmpty { null })
            .param("city", city?.trim()?.ifEmpty { null }).param("phone", normalized).param("bps", bps)
            .param("wallet", wallet.id).param("by", agentId).param("now", now).update()
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
        return MerchantDetail(view(m), payments)
    }

    fun view(m: Merchant) = MerchantView(
        m.id, m.code, m.name, m.category, m.city, m.phone, m.commissionBps, m.status,
        ledgerRepository.balance(m.walletAccountId), qrPayload(m.code), m.createdAt,
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
)

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
        return merchants.create(me.id, b.name, b.category, b.city, b.phone, b.commissionBps)
    }

    @GetMapping("/{id}")
    fun detail(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): MerchantDetail {
        requireRead(me)
        return merchants.detail(id)
    }

    @PostMapping("/{id}/status")
    fun status(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: MerchantStatusRequest): MerchantView {
        requireAdmin(me)
        return merchants.setStatus(me.id, id, b.status, b.reason)
    }
}
