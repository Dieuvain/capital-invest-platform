package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitExceededException
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.notifications.NotificationService
import jakarta.validation.Valid
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
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
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.pending-transfers")
data class PendingTransfersProperties(
    /** Délai laissé au proche pour ouvrir son compte. Ensuite, l'argent revient à l'expéditeur. */
    val validity: Duration = Duration.ofDays(7),
    /** Plafond d'un envoi à un numéro sans compte : le proche ouvre un compte au KYC 1. */
    val maxAmountMinor: Long = 10_000,
    /** Envois en attente à la fois, par expéditeur. */
    val maxPendingPerSender: Int = 3,
    /** Total en attente pour un même numéro, tous expéditeurs confondus. */
    val maxPendingPerPhoneMinor: Long = 50_000,
    /** Désactivé dans les tests, qui appellent eux-mêmes [PendingTransferService.expireDue]. */
    val pollerEnabled: Boolean = true,
)

enum class PendingTransferStatus { PENDING, CLAIMED, CANCELLED, EXPIRED }

/** Un envoi à un proche sans compte, tel que l'expéditeur le suit. */
data class PendingTransfer(
    val id: UUID,
    val recipientName: String,
    val phone: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val note: String?,
    val status: PendingTransferStatus,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val settledAt: OffsetDateTime?,
)

data class PendingTransferRequest(
    val toPhone: String,
    @field:Size(min = 1, max = 40) val recipientName: String,
    @field:Positive val amountMinor: Long,
    val pin: String,
    @field:Size(max = 140) val note: String? = null,
)

@Service
class PendingTransferService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val fees: FeeService,
    private val notifications: NotificationService,
    private val audit: AuditLog,
    private val props: PendingTransfersProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val pendingAccountId by lazy {
        ledgerRepository.findAccountByCode("PENDING_TRANSFERS_USD")?.id ?: error("Compte PENDING_TRANSFERS_USD absent")
    }
    private val feesAccountId by lazy {
        ledgerRepository.findAccountByCode("FEES_REVENUE_USD")?.id ?: error("Compte FEES_REVENUE_USD absent")
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /**
     * Met l'argent de côté pour un numéro qui n'a pas de compte et prévient le proche par SMS.
     * Le montant et les frais quittent tout de suite le portefeuille : les plafonds de l'expéditeur s'appliquent comme pour un envoi.
     */
    @Transactional
    fun send(idempotencyKey: String, senderId: UUID, r: PendingTransferRequest): Pair<PendingTransfer, Boolean> {
        find(senderId, idempotencyKey)?.let { return it to true }
        val phone = normalizeDrcPhone(r.toPhone) ?: throw BusinessException("INVALID_PHONE", "Numéro congolais attendu")
        val name = r.recipientName.trim()
        if (name.isEmpty() || name.length > 40) throw BusinessException("INVALID_RECIPIENT_NAME", "Donnez un nom de 1 à 40 caractères")
        if (r.amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        if (r.amountMinor > props.maxAmountMinor) {
            throw LimitExceededException("PENDING_TRANSFER_MAX", "Au plus ${money(props.maxAmountMinor)} pour un proche sans compte")
        }
        val sender = customers.findById(senderId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        if (sender.phone == phone) throw BusinessException("SAME_ACCOUNT", "Impossible d'envoyer à soi-même")
        // Un numéro qui a déjà un compte reçoit un envoi normal, tout de suite.
        if (customers.findByPhone(phone) != null) throw BusinessException("RECIPIENT_HAS_ACCOUNT", "Ce numéro a déjà un compte Capital Invest")
        if (sender.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Compte suspendu ou fermé")
        val wallet = ledgerRepository.findCustomerWallet(sender.id, Currency.USD)!!
        // Verrou du portefeuille : les plafonds et le nombre d'envois en attente tiennent même en concurrence.
        ledgerRepository.lockAccounts(listOf(wallet.id))
        val open = jdbc.sql("SELECT count(*) FROM pending_transfer WHERE sender_id = :s AND status = 'PENDING'")
            .param("s", sender.id).query(Int::class.java).single()
        if (open >= props.maxPendingPerSender) {
            throw LimitExceededException("PENDING_TRANSFERS_LIMIT", "Déjà ${props.maxPendingPerSender} envois en attente : attendez qu'ils soient reçus ou annulez-en un")
        }
        val forPhone = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM pending_transfer WHERE phone = :p AND status = 'PENDING'")
            .param("p", phone).query(Long::class.java).single()
        if (forPhone + r.amountMinor > props.maxPendingPerPhoneMinor) {
            throw LimitExceededException("RECIPIENT_LIMIT_REACHED", "Le destinataire ne peut pas recevoir ce montant")
        }
        limits.checkOutgoing(sender, wallet.id, r.amountMinor)

        val id = UUID.randomUUID()
        val fee = fees.transferFee(r.amountMinor)
        val now = now()
        val expiresAt = now.plus(props.validity)
        val note = r.note?.trim()?.take(140)?.ifEmpty { null }
        jdbc.sql(
            """INSERT INTO pending_transfer (id, sender_id, idempotency_key, phone, recipient_name, amount_minor, fee_minor, note, status, created_at, expires_at)
               VALUES (:id, :s, :k, :p, :n, :a, :f, :note, 'PENDING', :now, :exp)""",
        ).param("id", id).param("s", sender.id).param("k", idempotencyKey).param("p", phone).param("n", name)
            .param("a", r.amountMinor).param("f", fee).param("note", note).param("now", now).param("exp", expiresAt).update()
        ledger.post(
            PostingRequest(
                idempotencyKey = "invite:$id",
                type = "TRANSFER_INVITE",
                description = note,
                lines = listOf(
                    PostingLine(wallet.id, Direction.DEBIT, r.amountMinor),
                    PostingLine(pendingAccountId, Direction.CREDIT, r.amountMinor),
                    PostingLine(wallet.id, Direction.DEBIT, fee),
                    PostingLine(feesAccountId, Direction.CREDIT, fee),
                ),
            ),
        )
        notifications.transferInvite(id, phone, sender.fullName, r.amountMinor, expiresAt)
        return find(senderId, idempotencyKey)!! to false
    }

    fun list(senderId: UUID, maskPhone: Boolean): List<PendingTransfer> =
        jdbc.sql("SELECT * FROM pending_transfer WHERE sender_id = :s ORDER BY (status = 'PENDING') DESC, created_at DESC LIMIT 20")
            .param("s", senderId).query { rs, _ -> view(rs, maskPhone) }.list()

    /** L'expéditeur reprend son argent, frais compris, tant que le proche ne l'a pas reçu. */
    @Transactional
    fun cancel(senderId: UUID, id: UUID): PendingTransfer {
        val row = lockRow(id)?.takeIf { it.senderId == senderId }
            ?: throw NotFoundException("PENDING_TRANSFER_NOT_FOUND", "Envoi introuvable")
        if (row.status != "PENDING") throw BusinessException("PENDING_TRANSFER_SETTLED", "Cet envoi a déjà été reçu ou remboursé")
        refund(row, PendingTransferStatus.CANCELLED)
        audit.record(ActorType.CUSTOMER, senderId, "PENDING_TRANSFER_CANCELLED", "PENDING_TRANSFER", id)
        return list(senderId, maskPhone = true).first { it.id == id }
    }

    /**
     * Le proche vient d'ouvrir son compte avec ce numéro : tout ce qui l'attend lui est versé,
     * dans la transaction de l'inscription. Un envoi qui dépasserait son solde maximal revient à l'expéditeur.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claimFor(customer: Customer) {
        val ids = jdbc.sql("SELECT id FROM pending_transfer WHERE phone = :p AND status = 'PENDING' ORDER BY created_at")
            .param("p", customer.phone).query(UUID::class.java).list()
        if (ids.isEmpty()) return
        val wallet = ledgerRepository.findCustomerWallet(customer.id, Currency.USD)!!
        for (id in ids) {
            val row = lockRow(id)?.takeIf { it.status == "PENDING" } ?: continue
            try {
                limits.checkIncoming(customer, wallet.id, row.amountMinor)
            } catch (e: BusinessException) {
                refund(row, PendingTransferStatus.EXPIRED)
                continue
            }
            val tx = ledger.post(
                PostingRequest(
                    idempotencyKey = "invite-claim:$id",
                    type = "TRANSFER_INVITE_CLAIM",
                    description = row.note,
                    lines = listOf(
                        PostingLine(pendingAccountId, Direction.DEBIT, row.amountMinor),
                        PostingLine(wallet.id, Direction.CREDIT, row.amountMinor),
                    ),
                ),
            ).transaction
            jdbc.sql("UPDATE pending_transfer SET status = 'CLAIMED', settled_at = :now, claimed_by = :c WHERE id = :id")
                .param("now", now()).param("c", customer.id).param("id", id).update()
            notifications.transferClaimed(row.senderId, id, row.recipientName, row.amountMinor, tx.id)
        }
    }

    /** Envois non réclamés à temps : l'argent revient à l'expéditeur, frais compris. Renvoie le nombre de remboursements. */
    fun expireDue(): Int {
        val due = jdbc.sql("SELECT id FROM pending_transfer WHERE status = 'PENDING' AND expires_at <= :now ORDER BY expires_at LIMIT 100")
            .param("now", now()).query(UUID::class.java).list()
        var done = 0
        for (id in due) {
            try {
                if (self.expireOne(id)) done++
            } catch (e: Exception) {
                log.warn("Remboursement de l'envoi en attente {} : {}", id, e.toString())
            }
        }
        return done
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private lateinit var self: PendingTransferService

    @Transactional
    fun expireOne(id: UUID): Boolean {
        val row = lockRow(id) ?: return false
        if (row.status != "PENDING" || row.expiresAt.isAfter(now())) return false
        refund(row, PendingTransferStatus.EXPIRED)
        return true
    }

    private fun refund(row: Row, status: PendingTransferStatus) {
        val wallet = ledgerRepository.findCustomerWallet(row.senderId, Currency.USD)!!
        ledger.post(
            PostingRequest(
                idempotencyKey = "invite-refund:${row.id}",
                type = "TRANSFER_INVITE_REFUND",
                description = null,
                lines = listOfNotNull(
                    PostingLine(pendingAccountId, Direction.DEBIT, row.amountMinor),
                    row.feeMinor.takeIf { it > 0 }?.let { PostingLine(feesAccountId, Direction.DEBIT, it) },
                    PostingLine(wallet.id, Direction.CREDIT, row.amountMinor + row.feeMinor),
                ),
            ),
        )
        jdbc.sql("UPDATE pending_transfer SET status = :st, settled_at = :now WHERE id = :id")
            .param("st", status.name).param("now", now()).param("id", row.id).update()
    }

    private data class Row(
        val id: UUID, val senderId: UUID, val phone: String, val recipientName: String,
        val amountMinor: Long, val feeMinor: Long, val note: String?, val status: String, val expiresAt: OffsetDateTime,
    )

    private fun lockRow(id: UUID): Row? =
        jdbc.sql("SELECT * FROM pending_transfer WHERE id = :id FOR UPDATE").param("id", id).query { rs, _ ->
            Row(
                rs.getObject("id", UUID::class.java), rs.getObject("sender_id", UUID::class.java), rs.getString("phone"),
                rs.getString("recipient_name"), rs.getLong("amount_minor"), rs.getLong("fee_minor"), rs.getString("note"),
                rs.getString("status"), rs.getObject("expires_at", OffsetDateTime::class.java),
            )
        }.list().firstOrNull()

    private fun find(senderId: UUID, key: String): PendingTransfer? =
        jdbc.sql("SELECT * FROM pending_transfer WHERE sender_id = :s AND idempotency_key = :k")
            .param("s", senderId).param("k", key).query { rs, _ -> view(rs, maskPhone = false) }.list().firstOrNull()

    private fun view(rs: ResultSet, maskPhone: Boolean): PendingTransfer {
        val phone = rs.getString("phone")
        return PendingTransfer(
            rs.getObject("id", UUID::class.java), rs.getString("recipient_name"),
            if (maskPhone) HistoryService.maskPhone(phone) else phone,
            rs.getLong("amount_minor"), rs.getLong("fee_minor"), rs.getString("note"),
            PendingTransferStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("expires_at", OffsetDateTime::class.java),
            rs.getObject("settled_at", OffsetDateTime::class.java),
        )
    }

    private fun money(minor: Long) = NotificationService.money(minor)
}

@Component
class PendingTransferJobs(private val service: PendingTransferService, private val props: PendingTransfersProperties) {
    @Scheduled(fixedDelayString = "\${capital-invest.pending-transfers.poll-delay-ms:300000}", initialDelay = 60_000)
    fun run() {
        if (props.pollerEnabled) service.expireDue()
    }
}

@RestController
@RequestMapping("/v1/transfers/pending")
class PendingTransferController(private val service: PendingTransferService, private val auth: AuthService) {
    @PostMapping
    fun send(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: PendingTransferRequest,
    ): ResponseEntity<PendingTransfer> {
        // Vérifié dans sa propre transaction : un mauvais PIN reste compté.
        auth.confirmWithPin(customerId, body.pin)
        val (t, replayed) = service.send(key, customerId, body)
        return ResponseEntity.status(if (replayed) HttpStatus.OK else HttpStatus.CREATED).body(t)
    }

    @GetMapping
    fun list(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = service.list(customerId, maskPhone = true)

    @DeleteMapping("/{id}")
    fun cancel(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = service.cancel(customerId, id)
}
