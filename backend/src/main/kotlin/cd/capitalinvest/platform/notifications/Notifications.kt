package cd.capitalinvest.platform.notifications

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.auth.SmsGateway
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryItem
import cd.capitalinvest.platform.history.HistoryKind
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.payments.Operator
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.text.Normalizer
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Publié par le grand livre quand une nouvelle opération est écrite (pas lors d'un rejeu). */
data class MoneyMoved(val transactionId: UUID)

/** Publié après l'écriture d'une notification : l'envoi du SMS part dès la fin de la transaction. */
object NotificationQueued

@ConfigurationProperties("capital-invest.notifications")
data class NotificationProperties(
    /** Relance des SMS non partis. Désactivée dans les tests, qui l'appellent eux-mêmes. */
    val dispatcherEnabled: Boolean = true,
    val maxSmsAttempts: Int = 5,
)

enum class NotificationKind {
    TOPUP, TRANSFER_RECEIVED, TRANSFER_SENT, WITHDRAWAL_PAID, WITHDRAWAL_REFUNDED, MERCHANT_PAYMENT, MERCHANT_PAYMENT_RECEIVED, MERCHANT_PAYOUT_PAID, MERCHANT_PAYOUT_FAILED, MERCHANT_PAYOUT_ACCOUNT_CHANGED, MONEY_REQUEST, MONEY_REQUEST_DECLINED, ADJUSTMENT, KYC_APPROVED, KYC_REJECTED, PIN_CHANGED
}

data class NotificationView(
    val id: UUID,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    /** Opération liée : l'app ouvre son reçu. */
    val transactionId: UUID?,
    val read: Boolean,
    val createdAt: OffsetDateTime,
)

data class NotificationInbox(val items: List<NotificationView>, val unread: Int)

data class MarkReadRequest(val ids: List<UUID>? = null)

private data class Draft(val kind: NotificationKind, val title: String, val body: String, val sms: String?)

/**
 * Prévient le client de tout ce qui touche son argent ou son compte : un SMS (le canal que tout
 * téléphone reçoit) et une ligne dans la boîte de notifications de l'app.
 * Un SMS « envoi » qu'il n'a pas fait est souvent le premier signe d'un vol de compte : chaque
 * sortie d'argent est donc notifiée.
 */
@Service
class NotificationService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val history: HistoryService,
    private val ledger: LedgerRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** Dans la transaction du mouvement d'argent : la notification existe si, et seulement si, l'argent a bougé. */
    @EventListener
    fun onMoneyMoved(event: MoneyMoved) {
        val customerIds = jdbc.sql(
            """SELECT DISTINCT a.customer_id FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id
               WHERE e.transaction_id = :tx AND a.type = 'CUSTOMER_WALLET'""",
        ).param("tx", event.transactionId).query(UUID::class.java).list()
        val items = customerIds.associateWith { customerId ->
            val item = history.receipt(customerId, event.transactionId, maskPhones = true).item
            draftFor(item, balance(customerId))?.let { queue(customerId, "tx:${event.transactionId}:$customerId", it, event.transactionId) }
            item
        }
        // Paiement marchand : le caissier attend ce SMS pour laisser partir le client avec ses achats.
        val payment = items.values.firstOrNull { it.kind == HistoryKind.MERCHANT_PAYMENT } ?: return
        val payer = customers.findById(items.entries.first { it.value === payment }.key)
        jdbc.sql(
            """SELECT m.id, e.amount_minor FROM ledger_entry e JOIN merchant m ON m.wallet_account_id = e.account_id
               WHERE e.transaction_id = :tx AND e.direction = 'CREDIT'""",
        ).param("tx", event.transactionId).query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getLong("amount_minor") }
            .list().forEach { (merchantId, netMinor) ->
                val who = listOfNotNull(payer?.fullName?.let { ascii(it).take(24) }, payer?.phone?.let { HistoryService.maskPhone(it) }).joinToString(" ")
                queueForMerchant(
                    merchantId, "tx:${event.transactionId}:merchant:$merchantId",
                    Draft(
                        NotificationKind.MERCHANT_PAYMENT_RECEIVED, "Paiement reçu",
                        "${money(payment.amountMinor)} reçus de $who, ${money(netMinor)} après commission. Reçu ${payment.receiptNumber}.",
                        "Capital Invest: paiement recu ${money(payment.amountMinor)} de $who. Net ${money(netMinor)}. Ref ${payment.receiptNumber}",
                    ),
                    event.transactionId,
                )
            }
    }

    /** Le versement d'un retrait ne touche plus le portefeuille (l'argent était déjà bloqué) : il est annoncé à part. */
    fun withdrawalPaid(withdrawalId: UUID, customerId: UUID, operator: Operator, msisdn: String, paidMinor: Long, transactionId: UUID) {
        val amount = money(paidMinor)
        queue(
            customerId, "withdrawal-paid:$withdrawalId",
            Draft(
                NotificationKind.WITHDRAWAL_PAID, "Retrait versé",
                "$amount sont arrivés sur votre numéro ${operatorLabel(operator.name)} +$msisdn.",
                "Capital Invest: $amount envoyes sur votre ${ascii(operatorLabel(operator.name))} +$msisdn.",
            ),
            transactionId,
        )
    }

    /** Reversement d'un marchand versé, ou refusé par l'opérateur (l'argent est alors revenu sur son compte). */
    fun merchantPayout(withdrawalId: UUID, merchantId: UUID, operator: Operator, msisdn: String, amountMinor: Long, paid: Boolean, transactionId: UUID) {
        val op = operatorLabel(operator.name)
        val draft = if (paid) {
            Draft(
                NotificationKind.MERCHANT_PAYOUT_PAID, "Reversement versé",
                "${money(amountMinor)} versés sur votre $op +$msisdn.",
                "Capital Invest: reversement de ${money(amountMinor)} verse sur votre ${ascii(op)} +$msisdn.",
            )
        } else {
            Draft(
                NotificationKind.MERCHANT_PAYOUT_FAILED, "Reversement non versé",
                "$op n'a pas pu verser ${money(amountMinor)} sur +$msisdn. L'argent reste sur votre compte Capital Invest.",
                "Capital Invest: $op n'a pas pu verser ${money(amountMinor)} sur +$msisdn. L'argent reste sur votre compte. Appelez-nous.",
            )
        }
        queueForMerchant(merchantId, "merchant-payout:${if (paid) "paid" else "failed"}:$withdrawalId", draft, transactionId)
    }

    /** Un client demande de l'argent : SMS et notification au client sollicité, qui paie ou refuse dans l'app. */
    fun moneyRequest(requestId: UUID, payerId: UUID, requesterName: String, requesterPhone: String, amountMinor: Long, note: String?) {
        val why = note?.let { " ($it)" } ?: ""
        queue(
            payerId, "money-request:$requestId",
            Draft(
                NotificationKind.MONEY_REQUEST, "Demande d'argent",
                "$requesterName ($requesterPhone) vous demande ${money(amountMinor)}$why. Payez ou refusez dans l'app.",
                "Capital Invest: ${short(requesterName)} vous demande ${money(amountMinor)}. Ouvrez l'app pour payer ou refuser. Ne payez que si vous le connaissez.",
            ),
            null,
        )
    }

    /** Demande refusée : le demandeur le voit dans l'app, sans SMS. */
    fun moneyRequestDeclined(requestId: UUID, requesterId: UUID, payerName: String, amountMinor: Long) {
        queue(
            requesterId, "money-request-declined:$requestId",
            Draft(NotificationKind.MONEY_REQUEST_DECLINED, "Demande refusée", "$payerName a refusé votre demande de ${money(amountMinor)}.", null),
            null,
        )
    }

    /** Le compte de reversement a changé : le marchand est prévenu sur son numéro habituel. */
    fun merchantPayoutAccountChanged(merchantId: UUID, operator: Operator, msisdn: String) {
        val op = operatorLabel(operator.name)
        // Chaque changement est un événement à part, même deux dans la même seconde.
        queueForMerchant(
            merchantId, "merchant-payout-account:$merchantId:${UUID.randomUUID()}",
            Draft(
                NotificationKind.MERCHANT_PAYOUT_ACCOUNT_CHANGED, "Compte de reversement changé",
                "Vos reversements iront désormais sur $op +$msisdn.",
                "Capital Invest: vos reversements iront desormais sur ${ascii(op)} +$msisdn. Pas vous? Appelez-nous vite.",
            ),
            null,
        )
    }

    fun kycDecided(customerId: UUID, requestId: UUID, approved: Boolean) {
        val draft = if (approved) {
            Draft(
                NotificationKind.KYC_APPROVED, "Vous passez au niveau 2",
                "Votre identité est vérifiée. Vos plafonds sont relevés.",
                "Capital Invest: votre compte passe au niveau 2. Vos plafonds sont releves.",
            )
        } else {
            Draft(
                NotificationKind.KYC_REJECTED, "Demande de niveau 2 refusée",
                "Votre demande n'a pas été acceptée. Ouvrez « Passer au niveau 2 » pour voir pourquoi et recommencer.",
                "Capital Invest: votre demande de niveau 2 n'a pas ete acceptee. Ouvrez l'app pour voir pourquoi.",
            )
        }
        queue(customerId, "kyc:$requestId", draft, null)
    }

    fun pinChanged(customerId: UUID, at: OffsetDateTime) = queue(
        customerId, "pin:$customerId:${at.toEpochSecond()}",
        Draft(
            NotificationKind.PIN_CHANGED, "PIN changé",
            "Votre PIN vient d'être changé et vos autres sessions ont été fermées. Si ce n'est pas vous, appelez tout de suite le service client.",
            "Capital Invest: votre PIN vient d'etre change. Si ce n'est pas vous, appelez vite le service client.",
        ),
        null,
    )

    private fun balance(customerId: UUID): Long =
        ledger.findCustomerWallet(customerId, Currency.USD)?.let { ledger.balance(it.id) } ?: 0

    private fun draftFor(i: HistoryItem, balance: Long): Draft? {
        val solde = "Solde: ${money(balance)}"
        val ref = "Ref ${i.receiptNumber}"
        val operator = i.operator?.let(::operatorLabel)
        return when (i.kind) {
            HistoryKind.TOPUP -> Draft(
                NotificationKind.TOPUP, "Recharge reçue",
                "${money(i.amountMinor)} ajoutés depuis ${operator ?: "mobile money"}. Nouveau solde : ${money(balance)}.",
                "Capital Invest: recharge ${ascii(operator ?: "")} de ${money(i.amountMinor)} recue. $solde. $ref",
            )
            HistoryKind.TRANSFER_RECEIVED -> Draft(
                NotificationKind.TRANSFER_RECEIVED, "Argent reçu",
                "${i.counterpartyName ?: "Un client"} vous a envoyé ${money(i.amountMinor)}" +
                    (i.note?.let { " : « $it »" } ?: ".") + " Nouveau solde : ${money(balance)}.",
                "Capital Invest: vous avez recu ${money(i.amountMinor)} de ${short(i.counterpartyName)}. $solde. $ref",
            )
            HistoryKind.TRANSFER_SENT -> Draft(
                NotificationKind.TRANSFER_SENT, "Envoi effectué",
                "${money(i.amountMinor)} envoyés à ${i.counterpartyName ?: "un client"}, frais ${money(i.feeMinor)}. " +
                    "Nouveau solde : ${money(balance)}. Si ce n'est pas vous, appelez le service client.",
                "Capital Invest: envoi de ${money(i.amountMinor)} a ${short(i.counterpartyName)}, frais ${money(i.feeMinor)}. " +
                    "$solde. Pas vous? Appelez-nous.",
            )
            HistoryKind.WITHDRAWAL_REFUND -> Draft(
                NotificationKind.WITHDRAWAL_REFUNDED, "Retrait remboursé",
                "Votre retrait n'a pas été versé par l'opérateur. ${money(i.netMinor)} sont revenus sur votre compte, frais compris.",
                "Capital Invest: retrait non verse. ${money(i.netMinor)} rendus, frais compris. $solde. $ref",
            )
            HistoryKind.MERCHANT_PAYMENT -> Draft(
                NotificationKind.MERCHANT_PAYMENT, "Paiement effectué",
                "${money(i.amountMinor)} payés à ${i.counterpartyName ?: "un marchand"}. Nouveau solde : ${money(balance)}. " +
                    "Si ce n'est pas vous, appelez le service client.",
                "Capital Invest: paiement de ${money(i.amountMinor)} a ${short(i.counterpartyName)}. $solde. $ref. Pas vous? Appelez-nous.",
            )
            HistoryKind.ADJUSTMENT -> Draft(
                NotificationKind.ADJUSTMENT, "Solde corrigé",
                "Votre solde a été corrigé de ${money(i.netMinor)}. Nouveau solde : ${money(balance)}.",
                "Capital Invest: solde corrige de ${money(i.netMinor)}. $solde. $ref",
            )
            // Demande de retrait : l'app suit déjà son état, le client est prévenu au versement ou au remboursement.
            HistoryKind.WITHDRAWAL -> null
        }
    }

    private fun queue(customerId: UUID, dedupeKey: String, d: Draft, transactionId: UUID?) =
        insert(customerId, null, dedupeKey, d, transactionId)

    /** Le marchand n'a pas d'app : seul le SMS compte, la ligne sert de file d'envoi et de trace. */
    private fun queueForMerchant(merchantId: UUID, dedupeKey: String, d: Draft, transactionId: UUID?) =
        insert(null, merchantId, dedupeKey, d, transactionId)

    private fun insert(customerId: UUID?, merchantId: UUID?, dedupeKey: String, d: Draft, transactionId: UUID?) {
        val sms = d.sms?.let { ascii(it).take(160) }
        val inserted = jdbc.sql(
            """INSERT INTO notification (id, customer_id, merchant_id, dedupe_key, kind, title, body, transaction_id, sms_text, sms_status, next_attempt_at, created_at)
               VALUES (:id, :customer, :merchant, :key, :kind, :title, :body, :tx, :sms, :status, :next, :now)
               ON CONFLICT (dedupe_key) DO NOTHING""",
        ).param("id", UUID.randomUUID()).param("customer", customerId).param("merchant", merchantId).param("key", dedupeKey).param("kind", d.kind.name)
            .param("title", d.title).param("body", d.body).param("tx", transactionId).param("sms", sms)
            .param("status", if (sms == null) "NONE" else "PENDING").param("next", if (sms == null) null else now())
            .param("now", now()).update()
        if (inserted > 0 && sms != null) events.publishEvent(NotificationQueued)
    }

    fun inbox(customerId: UUID, limit: Int): NotificationInbox {
        val items = jdbc.sql(
            """SELECT id, kind, title, body, transaction_id, read_at IS NOT NULL AS read, created_at FROM notification
               WHERE customer_id = :c ORDER BY created_at DESC, id DESC LIMIT :limit""",
        ).param("c", customerId).param("limit", limit.coerceIn(1, 100)).query { rs, _ ->
            NotificationView(
                rs.getObject("id", UUID::class.java), NotificationKind.valueOf(rs.getString("kind")), rs.getString("title"),
                rs.getString("body"), rs.getObject("transaction_id", UUID::class.java), rs.getBoolean("read"),
                rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list()
        val unread = jdbc.sql("SELECT count(*) FROM notification WHERE customer_id = :c AND read_at IS NULL")
            .param("c", customerId).query(Int::class.java).single()
        return NotificationInbox(items, unread)
    }

    /** Marque comme lues les notifications données, ou toutes. On ne touche jamais celles d'un autre client. */
    @Transactional
    fun markRead(customerId: UUID, ids: List<UUID>?) {
        if (ids != null && ids.isEmpty()) return
        val filter = if (ids == null) "" else "AND id IN (:ids)"
        var spec = jdbc.sql("UPDATE notification SET read_at = :now WHERE customer_id = :c AND read_at IS NULL $filter")
            .param("now", now()).param("c", customerId)
        if (ids != null) spec = spec.param("ids", ids)
        spec.update()
    }

    companion object {
        fun money(minor: Long): String {
            val abs = kotlin.math.abs(minor)
            val units = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
            return "${if (minor < 0) "-" else ""}$units,${(abs % 100).toString().padStart(2, '0')} $"
        }

        /** Sans accents ni caractères hors de l'alphabet SMS de base, pour tenir dans un SMS de 160 caractères. */
        fun ascii(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace('’', '\'').replace('«', '"').replace('»', '"')
            .replace('•', '*').replace(Regex("[^\\x20-\\x7E]"), "")

        fun operatorLabel(name: String): String = when (name) {
            "MPESA" -> "M-Pesa"
            "AIRTEL" -> "Airtel Money"
            "ORANGE" -> "Orange Money"
            else -> name
        }

        private fun short(name: String?): String = name?.let { ascii(it).take(30) } ?: "un client"
    }
}

/**
 * Envoi des SMS en attente : tout de suite après chaque mouvement, puis relances espacées
 * (1, 2, 4, 8 minutes) si la passerelle ne répond pas. Après 5 essais, le SMS est abandonné
 * (la notification reste dans l'app).
 */
@Component
class NotificationDispatcher(
    private val jdbc: JdbcClient,
    private val sms: SmsGateway,
    private val properties: NotificationProperties,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Transaction neuve à chaque SMS, y compris quand on est appelé juste après la fin d'une autre. */
    private val tx = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun afterCommit(@Suppress("UNUSED_PARAMETER") event: NotificationQueued) {
        runCatching { dispatch() }.onFailure { log.warn("Envoi des SMS : {}", it.toString()) }
    }

    @Scheduled(fixedDelayString = "\${capital-invest.notifications.retry-delay-ms:30000}", initialDelay = 20_000)
    fun retry() {
        if (properties.dispatcherEnabled) dispatch()
    }

    /** Envoie les SMS dus. Renvoie le nombre de SMS partis. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun dispatch(limit: Int = 50): Int {
        var sent = 0
        repeat(limit) {
            val done = tx.execute { sendOne() } ?: return sent
            if (!done) return sent
            sent++
        }
        return sent
    }

    /** Un SMS dû, verrouillé le temps de l'envoi pour qu'aucun autre serveur ne l'envoie aussi. Faux : plus rien à envoyer. */
    private fun sendOne(): Boolean? {
        val now = now()
        val row = jdbc.sql(
            """SELECT n.id, n.sms_text, n.sms_attempts, COALESCE(c.phone, m.phone) AS phone FROM notification n
               LEFT JOIN customer c ON c.id = n.customer_id LEFT JOIN merchant m ON m.id = n.merchant_id
               WHERE n.sms_status = 'PENDING' AND n.next_attempt_at <= :now
               ORDER BY n.next_attempt_at LIMIT 1 FOR UPDATE OF n SKIP LOCKED""",
        ).param("now", now).query { rs, _ ->
            listOf(rs.getObject("id", UUID::class.java), rs.getString("sms_text"), rs.getInt("sms_attempts"), rs.getString("phone"))
        }.list().firstOrNull() ?: return false
        val (id, text, attempts, phone) = row
        try {
            sms.send(phone as String, text as String)
            jdbc.sql("UPDATE notification SET sms_status = 'SENT', sms_sent_at = :now, sms_attempts = sms_attempts + 1, last_error = NULL WHERE id = :id")
                .param("now", now).param("id", id).update()
            return true
        } catch (e: Exception) {
            val tries = (attempts as Int) + 1
            val giveUp = tries >= properties.maxSmsAttempts
            log.warn("SMS {} non parti (essai {}) : {}", id, tries, e.toString())
            jdbc.sql(
                """UPDATE notification SET sms_attempts = :tries, last_error = :err, sms_status = :status, next_attempt_at = :next
                   WHERE id = :id""",
            ).param("tries", tries).param("err", e.toString().take(300)).param("status", if (giveUp) "FAILED" else "PENDING")
                .param("next", if (giveUp) null else now.plusMinutes(1L shl (tries - 1))).param("id", id).update()
            return true
        }
    }
}

@RestController
@RequestMapping("/v1/me/notifications")
class NotificationController(private val notifications: NotificationService) {
    @GetMapping
    fun inbox(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestParam(defaultValue = "50") limit: Int): NotificationInbox =
        notifications.inbox(customerId, limit)

    /** Corps vide ou sans « ids » : tout est marqué comme lu. */
    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestBody(required = false) body: MarkReadRequest?) =
        notifications.markRead(customerId, body?.ids)
}
