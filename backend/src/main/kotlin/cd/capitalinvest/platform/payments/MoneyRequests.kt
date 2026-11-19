package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.notifications.NotificationService
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.money-requests")
data class MoneyRequestProperties(
    /** Une demande sans réponse expire au bout de ce délai. */
    val expiryDays: Long = 7,
    /** Contre le harcèlement : demandes qu'un client peut faire en 24 h… */
    val maxPerDay: Int = 10,
    /** … et demandes en attente vers un même client. */
    val maxPendingPerPayer: Int = 3,
)

/** PENDING : en attente ; PAID : payée ; DECLINED : refusée ; CANCELLED : annulée par le demandeur ; EXPIRED : sans réponse à temps. */
enum class MoneyRequestStatus { PENDING, PAID, DECLINED, CANCELLED, EXPIRED }

data class MoneyRequestView(
    val id: UUID,
    /** INCOMING : on vous demande de l'argent ; OUTGOING : vous avez demandé. */
    val direction: String,
    /** L'autre client : le demandeur pour une demande reçue, le client sollicité pour une demande faite. */
    val counterpartyName: String,
    val counterpartyPhone: String,
    val amountMinor: Long,
    val currency: Currency,
    val note: String?,
    val status: MoneyRequestStatus,
    /** Envoi qui a payé la demande : l'app ouvre son reçu. */
    val transactionId: UUID?,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val decidedAt: OffsetDateTime?,
)

data class MoneyRequestList(
    /** Demandes reçues encore à payer ou refuser, plus anciennes d'abord. */
    val toPay: List<MoneyRequestView>,
    /** Tout le reste, plus récent d'abord. */
    val recent: List<MoneyRequestView>,
)

data class MoneyRequestPaid(val request: MoneyRequestView, val transfer: TransferResponse)

/**
 * Demander de l'argent à un autre client. Le client sollicité est prévenu (SMS et notification),
 * puis paie avec son PIN ou refuse. Le paiement est un envoi ordinaire : frais, plafonds et reçu
 * habituels. Rien ne part sans son PIN.
 */
@Service
class MoneyRequestService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val transfers: TransferService,
    private val notifications: NotificationService,
    private val props: MoneyRequestProperties,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private data class Row(
        val id: UUID, val requesterId: UUID, val payerId: UUID, val amountMinor: Long, val note: String?, val status: String,
        val transactionId: UUID?, val createdAt: OffsetDateTime, val expiresAt: OffsetDateTime, val decidedAt: OffsetDateTime?,
    )

    @Transactional
    fun create(idempotencyKey: String, requesterId: UUID, payerPhone: String, amountMinor: Long, note: String?): Pair<MoneyRequestView, Boolean> {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val requester = customers.findById(requesterId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        jdbc.sql("SELECT * FROM money_request WHERE requester_id = :r AND idempotency_key = :k")
            .param("r", requesterId).param("k", idempotencyKey).query(::map).optional().orElse(null)
            ?.let { existing ->
                if (existing.amountMinor != amountMinor) {
                    throw ConflictException("IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour une autre demande")
                }
                return view(existing, requesterId) to true
            }
        val payer = normalizeDrcPhone(payerPhone)?.let(customers::findByPhone)
            ?: throw NotFoundException("RECIPIENT_NOT_FOUND", "Aucun client Capital Invest avec ce numéro")
        if (payer.id == requester.id) throw BusinessException("SAME_ACCOUNT", "Impossible de vous demander de l'argent à vous-même")
        if (requester.status != "ACTIVE" || payer.status != "ACTIVE") {
            throw BusinessException("CUSTOMER_NOT_ACTIVE", "Un des deux clients est suspendu ou fermé")
        }
        val now = now()
        val today = jdbc.sql("SELECT count(*) FROM money_request WHERE requester_id = :r AND created_at > :since")
            .param("r", requesterId).param("since", now.minusHours(24)).query(Int::class.java).single()
        if (today >= props.maxPerDay) {
            throw BusinessException("REQUEST_LIMIT", "Vous avez fait ${props.maxPerDay} demandes en 24 h. Réessayez demain.")
        }
        val pending = jdbc.sql(
            "SELECT count(*) FROM money_request WHERE requester_id = :r AND payer_id = :p AND status = 'PENDING' AND expires_at > :now",
        ).param("r", requesterId).param("p", payer.id).param("now", now).query(Int::class.java).single()
        if (pending >= props.maxPendingPerPayer) {
            throw BusinessException("REQUEST_LIMIT", "Ce client a déjà ${props.maxPendingPerPayer} demandes de vous en attente.")
        }
        val id = UUID.randomUUID()
        val text = note?.trim()?.take(140)?.ifEmpty { null }
        jdbc.sql(
            """INSERT INTO money_request (id, requester_id, payer_id, idempotency_key, amount_minor, currency, note, created_at, expires_at)
               VALUES (:id, :r, :p, :k, :amount, :cur, :note, :now, :exp)""",
        ).param("id", id).param("r", requesterId).param("p", payer.id).param("k", idempotencyKey).param("amount", amountMinor)
            .param("cur", Currency.USD.name).param("note", text).param("now", now).param("exp", now.plusDays(props.expiryDays)).update()
        notifications.moneyRequest(id, payer.id, requester.fullName, requester.phone, amountMinor, text)
        return view(get(id), requesterId) to false
    }

    fun list(customerId: UUID, limit: Int = 30): MoneyRequestList {
        val rows = jdbc.sql(
            """SELECT * FROM money_request WHERE requester_id = :c OR payer_id = :c
               ORDER BY created_at DESC, id DESC LIMIT :limit""",
        ).param("c", customerId).param("limit", limit.coerceIn(1, 100)).query(::map).list().map { view(it, customerId) }
        val (toPay, recent) = rows.partition { it.direction == "INCOMING" && it.status == MoneyRequestStatus.PENDING }
        return MoneyRequestList(toPay.reversed(), recent)
    }

    /** Payer une demande reçue : un envoi au demandeur, avec ses frais et ses plafonds. Rejouer ne paie jamais deux fois. */
    @Transactional
    fun pay(payerId: UUID, id: UUID): MoneyRequestPaid {
        val r = lockForPayer(payerId, id)
        if (r.status == "PAID") {
            // Rejeu après un paiement réussi (réseau coupé avant la réponse) : même envoi, rien de plus.
            val again = transfers.transfer("request:$id", payerId, requesterPhone(r), r.amountMinor, r.note)
            return MoneyRequestPaid(view(r, payerId), response(again))
        }
        requirePending(r)
        val outcome = transfers.transfer("request:$id", payerId, requesterPhone(r), r.amountMinor, r.note)
        jdbc.sql("UPDATE money_request SET status = 'PAID', transaction_id = :tx, decided_at = :now WHERE id = :id")
            .param("tx", outcome.posting.transaction.id).param("now", now()).param("id", id).update()
        return MoneyRequestPaid(view(get(id), payerId), response(outcome))
    }

    @Transactional
    fun decline(payerId: UUID, id: UUID): MoneyRequestView {
        val r = lockForPayer(payerId, id)
        if (r.status == "DECLINED") return view(r, payerId)
        requirePending(r)
        jdbc.sql("UPDATE money_request SET status = 'DECLINED', decided_at = :now WHERE id = :id").param("now", now()).param("id", id).update()
        val payer = customers.findById(payerId)!!
        notifications.moneyRequestDeclined(id, r.requesterId, payer.fullName, r.amountMinor)
        return view(get(id), payerId)
    }

    @Transactional
    fun cancel(requesterId: UUID, id: UUID): MoneyRequestView {
        val r = jdbc.sql("SELECT * FROM money_request WHERE id = :id AND requester_id = :c FOR UPDATE")
            .param("id", id).param("c", requesterId).query(::map).optional().orElse(null)
            ?: throw NotFoundException("REQUEST_NOT_FOUND", "Demande introuvable")
        if (r.status == "CANCELLED") return view(r, requesterId)
        requirePending(r)
        jdbc.sql("UPDATE money_request SET status = 'CANCELLED', decided_at = :now WHERE id = :id").param("now", now()).param("id", id).update()
        return view(get(id), requesterId)
    }

    private fun lockForPayer(payerId: UUID, id: UUID): Row =
        jdbc.sql("SELECT * FROM money_request WHERE id = :id AND payer_id = :c FOR UPDATE")
            .param("id", id).param("c", payerId).query(::map).optional().orElse(null)
            ?: throw NotFoundException("REQUEST_NOT_FOUND", "Demande introuvable")

    private fun requirePending(r: Row) {
        when (statusOf(r)) {
            MoneyRequestStatus.PENDING -> Unit
            MoneyRequestStatus.EXPIRED -> throw BusinessException("REQUEST_EXPIRED", "Cette demande a expiré")
            else -> throw BusinessException("REQUEST_CLOSED", "Cette demande a déjà été payée, refusée ou annulée")
        }
    }

    private fun requesterPhone(r: Row) = customers.findById(r.requesterId)!!.phone

    private fun response(o: TransferOutcome) =
        TransferResponse(o.posting.transaction, o.posting.replayed, o.amountMinor, o.feeMinor, o.amountMinor + o.feeMinor)

    private fun statusOf(r: Row): MoneyRequestStatus =
        if (r.status == "PENDING" && !now().isBefore(r.expiresAt)) MoneyRequestStatus.EXPIRED else MoneyRequestStatus.valueOf(r.status)

    private fun get(id: UUID): Row = jdbc.sql("SELECT * FROM money_request WHERE id = :id").param("id", id).query(::map).single()

    private fun view(r: Row, me: UUID): MoneyRequestView {
        val incoming = r.payerId == me
        val other = customers.findById(if (incoming) r.requesterId else r.payerId)!!
        return MoneyRequestView(
            r.id, if (incoming) "INCOMING" else "OUTGOING", other.fullName, other.phone, r.amountMinor, Currency.USD, r.note,
            statusOf(r), r.transactionId, r.createdAt, r.expiresAt, r.decidedAt,
        )
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Row(
        rs.getObject("id", UUID::class.java), rs.getObject("requester_id", UUID::class.java), rs.getObject("payer_id", UUID::class.java),
        rs.getLong("amount_minor"), rs.getString("note"), rs.getString("status"), rs.getObject("transaction_id", UUID::class.java),
        rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("expires_at", OffsetDateTime::class.java),
        rs.getObject("decided_at", OffsetDateTime::class.java),
    )
}

data class MoneyRequestCreate(
    @field:NotBlank val payerPhone: String,
    @field:Positive val amountMinor: Long,
    @field:Size(max = 140) val note: String? = null,
)

data class PinConfirmation(val pin: String)

@RestController
@RequestMapping("/v1/money-requests")
class MoneyRequestController(private val requests: MoneyRequestService, private val auth: AuthService) {

    @PostMapping
    fun create(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: MoneyRequestCreate,
    ): ResponseEntity<MoneyRequestView> {
        val (view, replayed) = requests.create(key, customerId, body.payerPhone, body.amountMinor, body.note)
        return ResponseEntity.status(if (replayed) HttpStatus.OK else HttpStatus.CREATED).body(view)
    }

    @GetMapping
    fun list(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): MoneyRequestList = requests.list(customerId)

    /** Payer se confirme par le PIN, comme un envoi. */
    @PostMapping("/{id}/pay")
    fun pay(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @RequestBody body: PinConfirmation): MoneyRequestPaid {
        auth.confirmWithPin(customerId, body.pin)
        return requests.pay(customerId, id)
    }

    @PostMapping("/{id}/decline")
    fun decline(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID): MoneyRequestView =
        requests.decline(customerId, id)

    @PostMapping("/{id}/cancel")
    fun cancel(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID): MoneyRequestView =
        requests.cancel(customerId, id)
}
