package cd.capitalinvest.platform.payments.bills

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.notifications.NotificationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** PREPAID : on achète de l'énergie, le facturier renvoie un code à taper sur le compteur. POSTPAID : on règle une facture. */
enum class BillKind { PREPAID, POSTPAID }

/** Facturiers proposés. Le format de la référence est vérifié avant d'interroger le facturier. */
enum class Biller(val label: String, val category: String, val kind: BillKind, val referenceLabel: String, val referenceDigits: IntRange) {
    SNEL_PREPAID("SNEL prépayé", "Électricité", BillKind.PREPAID, "Numéro de compteur", 11..11),
    REGIDESO("REGIDESO", "Eau", BillKind.POSTPAID, "Numéro d'abonné", 6..10),
    CANAL_PLUS("Canal+", "Télévision", BillKind.POSTPAID, "Numéro d'abonné", 8..14),
    DSTV("DStv", "Télévision", BillKind.POSTPAID, "Numéro de smartcard", 10..10),
}

data class BillerView(val code: Biller, val label: String, val category: String, val kind: BillKind, val referenceLabel: String)

@ConfigurationProperties("capital-invest.bills")
data class BillsProperties(
    val minAmountMinor: Long = 100,
    val maxAmountMinor: Long = 50_000,
    /** Relance d'un paiement sans réponse : d'abord après ce délai, puis de plus en plus espacée. */
    val firstCheckAfter: Duration = Duration.ofMinutes(1),
    /** Sans réponse sûre passé ce délai, un agent tranche : on ne rembourse jamais sur un doute. */
    val reviewAfter: Duration = Duration.ofHours(24),
    val pollerEnabled: Boolean = true,
)

/** Compte chez le facturier : le titulaire, et ce qu'il doit pour une facture. */
data class BillAccount(val biller: Biller, val reference: String, val holderName: String, val amountDueMinor: Long?)

/** REVIEW : toujours sans réponse sûre après 24 h, un agent tranche. */
enum class BillStatus { PENDING, PAID, FAILED, REVIEW }

data class BillPayment(
    val id: UUID,
    val biller: Biller,
    val billerName: String,
    val reference: String,
    val holderName: String,
    val amountMinor: Long,
    val currency: Currency,
    /** PENDING : le facturier n'a pas encore confirmé. FAILED : rien n'a été payé, le client est remboursé. */
    val status: BillStatus,
    /** Code à taper sur le compteur SNEL prépayé, groupé par 4 chiffres. */
    val token: String?,
    val failureMessage: String?,
    val transactionId: UUID,
    val createdAt: OffsetDateTime,
)

sealed interface BillResult {
    data class Paid(val reference: String, val token: String?) : BillResult
    data class Failed(val message: String) : BillResult
    data object Unknown : BillResult
}

/** Agrégateur de factures : un seul contrat pour plusieurs facturiers. */
interface BillConnector {
    val name: String
    fun lookup(biller: Biller, reference: String): BillAccount?
    fun pay(paymentId: UUID, biller: Biller, reference: String, amountMinor: Long): BillResult

    /** Statut d'un paiement resté sans réponse sûre, relu chez l'agrégateur. */
    fun check(paymentId: UUID, biller: Biller, reference: String, amountMinor: Long): BillResult
}

/**
 * Simulateur, en attendant le contrat avec un agrégateur.
 * Une référence qui finit par 0000 n'existe pas ; par 9999, le facturier refuse le paiement.
 * Par 5555 : pas de réponse au paiement, confirmé à la relance. Par 6666 : jamais de réponse.
 */
@Component
class SimulatorBillConnector : BillConnector {
    override val name = "SIMULATOR"

    private val names = listOf("KABEYA AMANI", "MBUYI GRACE", "ILUNGA PATRICK", "MUKENDI BENI", "NZUZI MAMA", "TSHIBANGU JOEL")

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    override fun lookup(biller: Biller, reference: String): BillAccount? {
        if (reference.endsWith("0000")) return null
        val h = hash("${biller.name}:$reference")
        val due = if (biller.kind == BillKind.POSTPAID) 1_000L + (h[1].toInt() and 0xff) * 50 else null
        return BillAccount(biller, reference, names[(h[0].toInt() and 0xff) % names.size], due)
    }

    override fun pay(paymentId: UUID, biller: Biller, reference: String, amountMinor: Long): BillResult =
        if (reference.endsWith("5555") || reference.endsWith("6666")) BillResult.Unknown else check(paymentId, biller, reference, amountMinor)

    override fun check(paymentId: UUID, biller: Biller, reference: String, amountMinor: Long): BillResult {
        if (reference.endsWith("6666")) return BillResult.Unknown
        if (reference.endsWith("9999")) return BillResult.Failed("${biller.label} n'accepte pas de paiement pour ce compte")
        val token = if (biller.kind == BillKind.PREPAID) {
            hash(paymentId.toString()).take(10).joinToString("") { "%02d".format((it.toInt() and 0xff) % 100) }
        } else {
            null
        }
        return BillResult.Paid("SIM-BILL-" + paymentId.toString().take(8).uppercase(), token)
    }
}

data class RecentBill(val biller: Biller, val billerName: String, val reference: String, val holderName: String)

/**
 * Paiement de factures, sur le modèle du crédit téléphone :
 * argent sorti du portefeuille sous verrou et après les plafonds, puis paiement chez le facturier hors transaction,
 * remboursement immédiat sur un refus certain, attente sans remboursement sur un doute.
 */
@Service
class BillService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val connector: BillConnector,
    private val notifications: NotificationService,
    private val props: BillsProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val billersAccountId by lazy { ledgerRepository.findAccountByCode("BILLERS_USD")?.id ?: error("Compte BILLERS_USD absent") }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    fun billers(): List<BillerView> = Biller.entries.map { BillerView(it, it.label, it.category, it.kind, it.referenceLabel) }

    private fun cleanReference(biller: Biller, reference: String): String {
        val digits = reference.filter { it.isDigit() }
        if (digits.length !in biller.referenceDigits || digits.length != reference.count { !it.isWhitespace() && it != '-' }) {
            val size = biller.referenceDigits.let { if (it.first == it.last) "${it.first} chiffres" else "${it.first} à ${it.last} chiffres" }
            throw BusinessException("INVALID_BILL_REFERENCE", "${biller.referenceLabel} : $size")
        }
        return digits
    }

    fun lookup(biller: Biller, reference: String): BillAccount {
        val ref = cleanReference(biller, reference)
        return connector.lookup(biller, ref)
            ?: throw NotFoundException("BILL_ACCOUNT_NOT_FOUND", "${biller.label} ne connaît pas ce ${biller.referenceLabel.lowercase()}")
    }

    fun pay(customerId: UUID, idempotencyKey: String, biller: Biller, reference: String, amountMinor: Long): BillPayment {
        if (amountMinor < props.minAmountMinor || amountMinor > props.maxAmountMinor) {
            throw BusinessException("BILL_AMOUNT", "Le paiement va de ${money(props.minAmountMinor)} à ${money(props.maxAmountMinor)}")
        }
        val ref = cleanReference(biller, reference)
        findByKey(customerId, idempotencyKey)?.let { return it }
        val account = lookup(biller, ref)
        val (payment, created) = tx.execute {
            val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
            findByKey(customerId, idempotencyKey)?.let { return@execute it to false }
            if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu")
            val wallet = ledgerRepository.findCustomerWallet(customerId, Currency.USD)!!
            ledgerRepository.lockAccounts(listOf(wallet.id))
            limits.checkOutgoing(customer, wallet.id, amountMinor)
            val posting = ledger.post(
                PostingRequest(
                    idempotencyKey = "bill:$customerId:$idempotencyKey",
                    type = "BILL_PAYMENT",
                    description = "${biller.label} $ref",
                    lines = listOf(
                        PostingLine(wallet.id, Direction.DEBIT, amountMinor),
                        PostingLine(billersAccountId, Direction.CREDIT, amountMinor),
                    ),
                ),
            )
            val id = UUID.randomUUID()
            val now = now()
            jdbc.sql(
                """INSERT INTO bill_payment (id, customer_id, idempotency_key, biller, reference, holder_name, amount_minor, status,
                                             provider, hold_transaction_id, next_check_at, created_at, updated_at)
                   VALUES (:id, :c, :key, :biller, :ref, :holder, :amount, 'PENDING', :provider, :hold, :next, :now, :now)""",
            ).param("id", id).param("c", customerId).param("key", idempotencyKey).param("biller", biller.name).param("ref", ref)
                .param("holder", account.holderName).param("amount", amountMinor).param("provider", connector.name)
                .param("hold", posting.transaction.id).param("next", now.plus(props.firstCheckAfter)).param("now", now).update()
            get(id) to true
        }!!
        if (!created) return payment
        val result = runCatching { connector.pay(payment.id, biller, ref, amountMinor) }
            .onFailure { log.error("Facture {} : pas de réponse du facturier : {}", payment.id, it.toString()) }
            .getOrDefault(BillResult.Unknown)
        return tx.execute { finish(customerId, payment.id, result) }!!
    }

    private fun finish(customerId: UUID, id: UUID, result: BillResult, byAgent: Boolean = false): BillPayment {
        val p = jdbc.sql("SELECT * FROM bill_payment WHERE id = :id FOR UPDATE").param("id", id).query(::map).single()
        if (p.status != BillStatus.PENDING && !(byAgent && p.status == BillStatus.REVIEW)) return p
        val now = now()
        when (result) {
            is BillResult.Paid -> {
                jdbc.sql("UPDATE bill_payment SET status = 'PAID', provider_reference = :ref, token = :token, next_check_at = NULL, updated_at = :now WHERE id = :id")
                    .param("ref", result.reference).param("token", result.token).param("now", now).param("id", id).update()
                // Le code du compteur doit survivre à l'app fermée ou au téléphone éteint : il part aussi par SMS.
                val paid = get(id)
                notifications.billPaid(customerId, paid.id, paid.transactionId, paid.billerName, paid.reference, paid.amountMinor, paid.token)
            }
            is BillResult.Failed -> {
                jdbc.sql("UPDATE bill_payment SET failure_message = :msg WHERE id = :id").param("msg", result.message.take(300)).param("id", id).update()
                val wallet = ledgerRepository.findCustomerWallet(customerId, Currency.USD)!!
                val refund = ledger.post(
                    PostingRequest(
                        idempotencyKey = "bill-refund:$id",
                        type = "BILL_REFUND",
                        description = result.message.take(140),
                        lines = listOf(
                            PostingLine(billersAccountId, Direction.DEBIT, p.amountMinor),
                            PostingLine(wallet.id, Direction.CREDIT, p.amountMinor),
                        ),
                    ),
                )
                jdbc.sql("UPDATE bill_payment SET status = 'FAILED', refund_transaction_id = :tx, next_check_at = NULL, updated_at = :now WHERE id = :id")
                    .param("tx", refund.transaction.id).param("now", now).param("id", id).update()
            }
            BillResult.Unknown -> Unit
        }
        return get(id)
    }

    /** Relance les paiements sans réponse dont l'heure est venue. Appelé chaque minute. */
    fun checkDue(limit: Int = 50) {
        val ids = jdbc.sql("SELECT id FROM bill_payment WHERE status = 'PENDING' AND next_check_at <= :now ORDER BY next_check_at LIMIT :limit")
            .param("now", now()).param("limit", limit).query(UUID::class.java).list()
        for (id in ids) runCatching { checkOne(id) }.onFailure { log.error("Relance de la facture {} : {}", id, it.toString()) }
    }

    fun checkOne(id: UUID): BillPayment {
        val p = get(id)
        if (p.status != BillStatus.PENDING) return p
        val customerId = customerOf(id)
        val result = runCatching { connector.check(p.id, p.biller, p.reference, p.amountMinor) }.getOrDefault(BillResult.Unknown)
        return tx.execute {
            if (result != BillResult.Unknown) return@execute finish(customerId, id, result)
            val now = now()
            val checks = jdbc.sql("SELECT checks FROM bill_payment WHERE id = :id FOR UPDATE").param("id", id).query(Int::class.java).single() + 1
            if (!p.createdAt.plus(props.reviewAfter).isAfter(now)) {
                jdbc.sql(
                    """UPDATE bill_payment SET status = 'REVIEW', checks = :n, next_check_at = NULL, updated_at = :now,
                              review_reason = :reason WHERE id = :id AND status = 'PENDING'""",
                ).param("n", checks).param("now", now).param("id", id)
                    .param("reason", "Pas de réponse sûre de l'agrégateur après ${props.reviewAfter.toHours()} h").update()
            } else {
                val wait = props.firstCheckAfter.multipliedBy(1L shl checks.coerceAtMost(6)).coerceAtMost(Duration.ofHours(1))
                jdbc.sql("UPDATE bill_payment SET checks = :n, next_check_at = :next, updated_at = :now WHERE id = :id AND status = 'PENDING'")
                    .param("n", checks).param("next", now.plus(wait)).param("now", now).param("id", id).update()
            }
            get(id)
        }!!
    }

    /**
     * Décision d'un agent après vérification auprès de l'agrégateur. Pour un compteur prépayé, « payé » exige le code
     * du compteur obtenu de l'agrégateur : sans lui, le client a payé pour rien.
     */
    fun resolve(id: UUID, paid: Boolean, note: String, token: String?): BillPayment {
        val p = get(id)
        if (p.status != BillStatus.PENDING && p.status != BillStatus.REVIEW) {
            throw BusinessException("ALREADY_RESOLVED", "Ce paiement est déjà terminé")
        }
        val code = token?.filter { it.isDigit() }?.ifEmpty { null }
        if (paid && p.biller.kind == BillKind.PREPAID && (code == null || code.length != 20)) {
            throw BusinessException("TOKEN_REQUIRED", "Saisissez le code du compteur à 20 chiffres donné par l'agrégateur")
        }
        val result = if (paid) BillResult.Paid("AGENT: ${note.take(80)}", code) else BillResult.Failed("${p.billerName} n'a pas reçu le paiement")
        return tx.execute { finish(customerOf(id), id, result, byAgent = true) }!!
    }

    fun get(id: UUID): BillPayment =
        jdbc.sql("SELECT * FROM bill_payment WHERE id = :id").param("id", id).query(::map).optional().orElse(null)
            ?: throw NotFoundException("BILL_NOT_FOUND", "Paiement introuvable")

    fun forCustomer(customerId: UUID, id: UUID): BillPayment =
        get(id).takeIf { customerOf(id) == customerId } ?: throw NotFoundException("BILL_NOT_FOUND", "Paiement introuvable")

    private fun customerOf(id: UUID): UUID =
        jdbc.sql("SELECT customer_id FROM bill_payment WHERE id = :id").param("id", id).query(UUID::class.java).single()

    /** Les comptes déjà payés, pour repayer en un geste (le même compteur chaque mois). */
    fun recent(customerId: UUID): List<RecentBill> =
        jdbc.sql(
            """SELECT DISTINCT ON (biller, reference) biller, reference, holder_name, created_at FROM bill_payment
               WHERE customer_id = :c AND status <> 'FAILED' ORDER BY biller, reference, created_at DESC""",
        ).param("c", customerId).query { rs, _ ->
            val b = Biller.valueOf(rs.getString("biller"))
            rs.getObject("created_at", OffsetDateTime::class.java) to RecentBill(b, b.label, rs.getString("reference"), rs.getString("holder_name"))
        }.list().sortedByDescending { it.first }.take(6).map { it.second }

    private fun findByKey(customerId: UUID, key: String): BillPayment? =
        jdbc.sql("SELECT * FROM bill_payment WHERE customer_id = :c AND idempotency_key = :k")
            .param("c", customerId).param("k", key).query(::map).optional().orElse(null)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): BillPayment {
        val biller = Biller.valueOf(rs.getString("biller"))
        return BillPayment(
            id = rs.getObject("id", UUID::class.java),
            biller = biller,
            billerName = biller.label,
            reference = rs.getString("reference"),
            holderName = rs.getString("holder_name"),
            amountMinor = rs.getLong("amount_minor"),
            currency = Currency.USD,
            status = BillStatus.valueOf(rs.getString("status")),
            token = rs.getString("token")?.let(::formatToken),
            failureMessage = rs.getString("failure_message"),
            transactionId = rs.getObject("hold_transaction_id", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        )
    }

    private fun money(minor: Long) = "%d,%02d \$".format(minor / 100, minor % 100)

    companion object {
        /** « 12345678901234567890 » devient « 1234 5678 9012 3456 7890 », comme sur le compteur. */
        fun formatToken(t: String) = t.chunked(4).joinToString(" ")
    }
}

data class BillLookupRequest(val biller: Biller, val reference: String)

data class BillPayRequest(
    val biller: Biller,
    val reference: String,
    @field:Positive val amountMinor: Long,
    /** De l'argent quitte le compte : le PIN est demandé, comme pour un envoi. */
    val pin: String,
)

@RestController
@RequestMapping("/v1/bills")
class BillController(private val bills: BillService, private val auth: AuthService) {
    @GetMapping("/billers")
    fun billers() = bills.billers()

    /** Avant de payer : le nom du titulaire chez le facturier, pour vérifier le compteur ou l'abonnement. */
    @PostMapping("/lookup")
    fun lookup(@RequestBody body: BillLookupRequest) = bills.lookup(body.biller, body.reference)

    @PostMapping
    fun pay(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: BillPayRequest,
    ): ResponseEntity<BillPayment> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.status(HttpStatus.CREATED).body(bills.pay(customerId, key, body.biller, body.reference, body.amountMinor))
    }

    @GetMapping("/recent")
    fun recent(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = bills.recent(customerId)

    @GetMapping("/{id}")
    fun get(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = bills.forCustomer(customerId, id)
}
