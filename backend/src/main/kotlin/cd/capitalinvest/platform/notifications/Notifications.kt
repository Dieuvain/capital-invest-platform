package cd.capitalinvest.platform.notifications

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.auth.SmsGateway
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryItem
import cd.capitalinvest.platform.history.HistoryKind
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.history.HistoryStatus
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
import cd.capitalinvest.platform.common.BusinessException
import org.springframework.web.bind.annotation.PutMapping
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Publié par le grand livre quand une nouvelle opération est écrite (pas lors d'un rejeu). */
data class MoneyMoved(val transactionId: UUID)

/** Seuil d'alerte de solde bas ; null : pas d'alerte. */
data class LowBalanceSetting(val thresholdMinor: Long?)

/** Publié après l'écriture d'une notification : l'envoi du SMS part dès la fin de la transaction. */
object NotificationQueued

@ConfigurationProperties("capital-invest.notifications")
data class NotificationProperties(
    /** Relance des SMS non partis. Désactivée dans les tests, qui l'appellent eux-mêmes. */
    val dispatcherEnabled: Boolean = true,
    val maxSmsAttempts: Int = 5,
)

enum class NotificationKind {
    TOPUP, TRANSFER_RECEIVED, TRANSFER_SENT, WITHDRAWAL_PAID, WITHDRAWAL_REFUNDED, MERCHANT_PAYMENT, MERCHANT_PAYMENT_RECEIVED, MERCHANT_PAYOUT_PAID, MERCHANT_PAYOUT_FAILED, MERCHANT_PAYOUT_ACCOUNT_CHANGED, MONEY_REQUEST, MONEY_REQUEST_DECLINED, ADJUSTMENT, KYC_APPROVED, KYC_REJECTED, PIN_CHANGED, NEW_DEVICE, AIRTIME_REFUNDED, BILL_PAID, BILL_REFUNDED, COMPLAINT_RECEIVED, COMPLAINT_ANSWERED, SCHEDULED_TRANSFER_FAILED, ACCOUNT_BLOCKED, ACCOUNT_UNBLOCKED, DAILY_LIMIT_RAISE_PENDING, ACCOUNT_CLOSED, LOW_BALANCE, TRANSFER_PENDING, TRANSFER_INVITE, TRANSFER_CLAIMED, TRANSFER_REFUNDED, POT_CONTRIBUTION_SENT, POT_CONTRIBUTION_RECEIVED, POT_PAID_OUT, CARD_PAYMENT, CARD_REFUND, CARD_READY, CARD_BLOCKED
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
            val balance = balance(customerId)
            draftFor(item, balance)?.let { queue(customerId, "tx:${event.transactionId}:$customerId", it, event.transactionId) }
            checkLowBalance(customerId, balance, event.transactionId)
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

    /** Le client a bloqué son compte. Le SMS sert de preuve si quelqu'un d'autre l'a fait avec son PIN. */
    fun accountBlocked(customerId: UUID, at: OffsetDateTime) = queue(
        customerId, "blocked:$customerId:${at.toEpochSecond()}",
        Draft(
            NotificationKind.ACCOUNT_BLOCKED, "Compte bloqué",
            "Votre compte est bloqué à votre demande : plus personne ne peut se connecter ni envoyer votre argent. Pour le débloquer, présentez-vous en agence avec votre pièce d'identité.",
            "Capital Invest: votre compte est bloque a votre demande. Pour le debloquer, presentez-vous en agence avec votre piece d'identite.",
        ),
        null,
    )

    /** Hausse du plafond demandée : le SMS laisse 24 h au vrai titulaire pour réagir si ce n'est pas lui. */
    fun dailyLimitRaisePending(customerId: UUID, dailyOutMinor: Long, hours: Long, at: OffsetDateTime) = queue(
        customerId, "limit-raise:$customerId:${at.toEpochSecond()}",
        Draft(
            NotificationKind.DAILY_LIMIT_RAISE_PENDING, "Hausse du plafond demandée",
            "Votre plafond d'envoi passera à ${money(dailyOutMinor)} par jour dans $hours heures. Si ce n'est pas vous, annulez la hausse dans Sécurité ou bloquez votre compte.",
            "Capital Invest: votre plafond d'envoi passera a ${ascii(money(dailyOutMinor))} par jour dans $hours h. Si ce n'est pas vous, bloquez votre compte.",
        ),
        null,
    )

    /** Au proche qui n'a pas de compte : seul un SMS peut l'atteindre. */
    fun transferInvite(pendingId: UUID, phone: String, senderName: String, amountMinor: Long, expiresAt: OffsetDateTime) = insert(
        null, null, phone, "invite:$pendingId",
        Draft(
            NotificationKind.TRANSFER_INVITE, "Argent en attente",
            "",
            "Capital Invest: ${short(senderName)} vous a envoye ${money(amountMinor)}. Ouvrez un compte avec ce numero avant le " +
                "${expiresAt.atZoneSameInstant(java.time.ZoneId.of("Africa/Lubumbashi")).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))} pour les recevoir.",
        ),
        null,
    )

    /** À l'expéditeur, quand le proche a ouvert son compte et reçu l'argent. */
    fun transferClaimed(senderId: UUID, pendingId: UUID, recipientName: String, amountMinor: Long, transactionId: UUID) = queue(
        senderId, "invite-claimed:$pendingId",
        Draft(
            NotificationKind.TRANSFER_CLAIMED, "Envoi reçu",
            "$recipientName a ouvert son compte Capital Invest et a reçu vos ${money(amountMinor)}.",
            "Capital Invest: ${short(recipientName)} a ouvert son compte et a recu vos ${money(amountMinor)}.",
        ),
        transactionId,
    )

    /**
     * À l'organisateur, à chaque participation : dans l'app seulement. Un SMS par participation
     * serait trop pour une cagnotte de deuil qui en reçoit des dizaines ; le SMS part à la clôture.
     */
    fun potContribution(ownerId: UUID, transactionId: UUID, title: String, contributorName: String, amountMinor: Long, message: String?, collectedMinor: Long) = queue(
        ownerId, "pot-received:$transactionId",
        Draft(
            NotificationKind.POT_CONTRIBUTION_RECEIVED, "Nouvelle participation",
            "$contributorName a versé ${money(amountMinor)} à « $title »" + (message?.let { " : « $it »." } ?: ".") +
                " Total collecté : ${money(collectedMinor)}.",
            null,
        ),
        null,
    )

    /** Compte fermé : seul le SMS compte, le client ne peut plus ouvrir l'app. */
    /** Carte physique livrée à l'agence : le client vient la chercher avec sa pièce d'identité. */
    fun cardReady(customerId: UUID, cardId: UUID, branch: String, last4: String) = queue(
        customerId, "card-ready:$cardId",
        Draft(
            NotificationKind.CARD_READY, "Votre carte est arrivée",
            "Votre carte •••• $last4 vous attend à l'agence $branch. Venez avec votre pièce d'identité, puis activez-la dans l'app avec le code remis avec la carte.",
            "Capital Invest: votre carte *$last4 vous attend a l'agence ${ascii(branch)}. Venez avec votre piece d'identite.",
        ),
        null,
    )

    /** Carte bloquée : le SMS prévient le vrai titulaire si quelqu'un d'autre l'a fait. */
    fun cardBlocked(customerId: UUID, cardId: UUID, virtual: Boolean, last4: String) = queue(
        customerId, "card-blocked:$cardId",
        Draft(
            NotificationKind.CARD_BLOCKED, "Carte bloquée",
            "Votre carte ${if (virtual) "virtuelle" else "physique"} •••• $last4 est bloquée définitivement : plus aucun paiement ne passera. " +
                "Vous pouvez la remplacer depuis l'écran Cartes. Si ce n'est pas vous, appelez le service client.",
            "Capital Invest: votre carte *$last4 est bloquee. Si ce n'est pas vous, appelez vite le service client.",
        ),
        null,
    )

    fun accountClosed(customerId: UUID, at: OffsetDateTime) = queue(
        customerId, "closed:$customerId",
        Draft(
            NotificationKind.ACCOUNT_CLOSED, "Compte fermé",
            "Votre compte Capital Invest est fermé. Merci de nous avoir fait confiance. Si ce n'est pas vous, appelez tout de suite le service client.",
            "Capital Invest: votre compte est ferme. Merci de votre confiance. Si ce n'est pas vous, appelez vite le service client.",
        ),
        null,
    )

    fun accountUnblocked(customerId: UUID, at: OffsetDateTime) = queue(
        customerId, "unblocked:$customerId:${at.toEpochSecond()}",
        Draft(
            NotificationKind.ACCOUNT_UNBLOCKED, "Compte débloqué",
            "Votre compte est débloqué. Si votre téléphone a été volé, changez votre PIN dans Sécurité dès votre connexion.",
            "Capital Invest: votre compte est debloque. Si votre telephone a ete vole, changez votre PIN des votre connexion.",
        ),
        null,
    )

    /** Connexion depuis un téléphone jamais vu sur ce compte : souvent le premier signe d'un vol de PIN. */
    fun newDevice(customerId: UUID, deviceName: String?, at: OffsetDateTime) {
        val phone = deviceName?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        queue(
            customerId, "device:$customerId:${at.toEpochSecond()}",
            Draft(
                NotificationKind.NEW_DEVICE, "Nouvelle connexion",
                "Votre compte vient d'être ouvert sur un autre téléphone$phone. Si ce n'est pas vous, changez votre PIN dans Sécurité et appelez le service client.",
                "Capital Invest: connexion a votre compte sur un nouveau telephone${ascii(phone).take(40)}. Si ce n'est pas vous, appelez vite le service client.",
            ),
            null,
        )
    }

    /** Facture payée. Pour un compteur prépayé, le SMS porte le code à taper : le client l'a même sans l'app. */
    fun billPaid(customerId: UUID, paymentId: UUID, transactionId: UUID, biller: String, reference: String, amountMinor: Long, token: String?) {
        queue(
            customerId, "bill:$paymentId",
            Draft(
                NotificationKind.BILL_PAID, "Facture payée",
                "${money(amountMinor)} payés à $biller pour le $reference." +
                    (token?.let { " Code à taper sur le compteur : $it." } ?: ""),
                token?.let { "Capital Invest: ${ascii(biller)} compteur $reference, ${money(amountMinor)}. Code: $it" }
                    ?: "Capital Invest: ${money(amountMinor)} payes a ${ascii(biller)} pour le $reference.",
            ),
            transactionId,
        )
    }

    /** Accusé de réception : le numéro et la date de réponse promise. */
    fun complaintReceived(customerId: UUID, complaintId: UUID, reference: String, dueDate: LocalDate, transactionId: UUID?) {
        val due = dueDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        queue(
            customerId, "complaint:$complaintId:received",
            Draft(
                NotificationKind.COMPLAINT_RECEIVED, "Réclamation reçue",
                "Votre réclamation $reference est enregistrée. Nous vous répondons au plus tard le $due.",
                "Capital Invest: reclamation $reference recue. Reponse au plus tard le $due.",
            ),
            transactionId,
        )
    }

    fun complaintAnswered(customerId: UUID, complaintId: UUID, reference: String, transactionId: UUID?) {
        queue(
            customerId, "complaint:$complaintId:answered",
            Draft(
                NotificationKind.COMPLAINT_ANSWERED, "Réponse à votre réclamation",
                "Le service client a répondu à votre réclamation $reference. Ouvrez « Mes réclamations » pour la lire.",
                "Capital Invest: reponse a votre reclamation $reference. Lisez-la dans l'app, menu Plus > Mes reclamations.",
            ),
            transactionId,
        )
    }

    /** Échéance d'un envoi programmé qui n'est pas partie : le client peut recharger et envoyer lui-même. */
    fun scheduledTransferFailed(
        customerId: UUID, scheduleId: UUID, occurrence: LocalDate, recipientName: String, amountMinor: Long, reason: String, paused: Boolean,
    ) {
        val then = if (paused) " L'envoi programmé est mis en pause après 3 échecs : reprenez-le dans « Envois programmés »." else " Le prochain partira à la date prévue."
        queue(
            customerId, "scheduled:$scheduleId:$occurrence",
            Draft(
                NotificationKind.SCHEDULED_TRANSFER_FAILED, "Envoi programmé non effectué",
                "L'envoi de ${money(amountMinor)} à $recipientName n'est pas parti : $reason.$then",
                "Capital Invest: envoi programme de ${money(amountMinor)} a ${ascii(recipientName).take(24)} non effectue: ${ascii(reason)}." +
                    if (paused) " Envoi mis en pause." else "",
            ),
            null,
        )
    }

    /**
     * Alerte de solde bas : une seule fois en passant sous le seuil. Elle se réarme quand le solde
     * repasse au-dessus, pour ne pas envoyer un SMS à chaque petit achat.
     */
    private fun checkLowBalance(customerId: UUID, balance: Long, transactionId: UUID) {
        // Une seule requête : elle ne change la ligne que si l'état bascule, et dit alors quel était le seuil.
        val threshold = jdbc.sql(
            """UPDATE customer SET low_balance_alerted = (:balance < low_balance_minor)
               WHERE id = :id AND low_balance_minor IS NOT NULL AND low_balance_alerted <> (:balance < low_balance_minor)
               RETURNING low_balance_minor""",
        ).param("balance", balance).param("id", customerId).query(Long::class.java).optional().orElse(null) ?: return
        if (balance < threshold) {
            queue(
                customerId, "low-balance:$customerId:$transactionId",
                Draft(
                    NotificationKind.LOW_BALANCE, "Solde bas",
                    "Votre solde est de ${money(balance)}, sous votre seuil d'alerte de ${money(threshold)}. Rechargez depuis M-Pesa, Airtel Money ou Orange Money.",
                    "Capital Invest: votre solde est de ${money(balance)}, sous votre seuil de ${money(threshold)}.",
                ),
                null,
            )
        }
    }

    fun lowBalanceSetting(customerId: UUID): LowBalanceSetting = LowBalanceSetting(
        jdbc.sql("SELECT low_balance_minor FROM customer WHERE id = :id").param("id", customerId).query { rs, _ -> rs.getObject("low_balance_minor") as Long? }.single(),
    )

    /** Choisir ou retirer le seuil. Pas d'alerte tout de suite si le solde est déjà dessous : le client le voit à l'écran. */
    @Transactional
    fun setLowBalance(customerId: UUID, thresholdMinor: Long?): LowBalanceSetting {
        if (thresholdMinor != null && thresholdMinor !in 100..100_000) {
            throw BusinessException("INVALID_THRESHOLD", "Choisissez un seuil entre 1,00 $ et 1 000,00 $")
        }
        jdbc.sql("UPDATE customer SET low_balance_minor = :t, low_balance_alerted = :a WHERE id = :id")
            .param("t", thresholdMinor).param("a", thresholdMinor != null && balance(customerId) < thresholdMinor).param("id", customerId).update()
        return LowBalanceSetting(thresholdMinor)
    }

    private fun balance(customerId: UUID): Long =
        ledger.findCustomerWallet(customerId, Currency.USD)?.let { ledger.balance(it.id) } ?: 0

    private fun draftFor(i: HistoryItem, balance: Long): Draft? {
        val solde = "Solde: ${money(balance)}"
        val ref = "Ref ${i.receiptNumber}"
        val operator = i.operator?.let(::operatorLabel)
        // Envoi à un proche sans compte : l'argent attend qu'il ouvre son compte.
        if (i.kind == HistoryKind.TRANSFER_SENT && i.status == HistoryStatus.PENDING) return Draft(
            NotificationKind.TRANSFER_PENDING, "Envoi en attente",
            "${money(i.amountMinor)} mis de côté pour ${i.counterpartyName ?: "votre proche"}, frais ${money(i.feeMinor)}. " +
                "Il les reçoit en ouvrant son compte Capital Invest avec son numéro. Sinon, ils vous reviennent, frais compris. " +
                "Nouveau solde : ${money(balance)}.",
            "Capital Invest: ${money(i.amountMinor)} en attente pour ${short(i.counterpartyName)}, frais ${money(i.feeMinor)}. " +
                "$solde. Pas vous? Appelez-nous.",
        )
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
            HistoryKind.TRANSFER_REFUND -> Draft(
                NotificationKind.TRANSFER_REFUNDED, "Envoi remboursé",
                "${i.counterpartyName ?: "Votre proche"} n'a pas reçu votre envoi : ${money(i.netMinor)} sont revenus sur votre compte, frais compris.",
                "Capital Invest: envoi a ${short(i.counterpartyName)} non reclame. ${money(i.netMinor)} rendus, frais compris. $solde. $ref",
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
            HistoryKind.POT_CONTRIBUTION -> Draft(
                NotificationKind.POT_CONTRIBUTION_SENT, "Participation envoyée",
                "${money(i.amountMinor)} versés à la cagnotte « ${i.counterpartyName ?: ""} ». Nouveau solde : ${money(balance)}. " +
                    "Si ce n'est pas vous, appelez le service client.",
                "Capital Invest: ${money(i.amountMinor)} verses a la cagnotte ${short(i.counterpartyName)}. $solde. $ref. Pas vous? Appelez-nous.",
            )
            HistoryKind.POT_PAYOUT -> Draft(
                NotificationKind.POT_PAID_OUT, "Cagnotte versée",
                "La cagnotte « ${i.counterpartyName ?: ""} » est clôturée : ${money(i.amountMinor)} sont sur votre compte. Nouveau solde : ${money(balance)}.",
                "Capital Invest: cagnotte ${short(i.counterpartyName)} cloturee, ${money(i.amountMinor)} sur votre compte. $solde. $ref",
            )
            HistoryKind.CARD_PAYMENT -> Draft(
                NotificationKind.CARD_PAYMENT, "Paiement par carte",
                "${money(i.amountMinor)} payés par carte à ${i.counterpartyName ?: "un commerçant"}. Nouveau solde : ${money(balance)}. " +
                    "Si ce n'est pas vous, gelez la carte dans l'app et appelez le service client.",
                "Capital Invest: carte, ${money(i.amountMinor)} a ${short(i.counterpartyName)}. $solde. $ref. Pas vous? Gelez la carte.",
            )
            HistoryKind.CARD_REFUND -> Draft(
                NotificationKind.CARD_REFUND, "Paiement par carte annulé",
                "${i.counterpartyName ?: "Le commerçant"} a annulé un paiement par carte : ${money(i.amountMinor)} sont revenus sur votre compte.",
                "Capital Invest: paiement carte annule par ${short(i.counterpartyName)}, ${money(i.amountMinor)} rendus. $solde. $ref",
            )
            // Le client vient de commander ou remplacer sa carte dans l'app, avec le prix affiché.
            HistoryKind.CARD_FEE -> null
            HistoryKind.ADJUSTMENT -> Draft(
                NotificationKind.ADJUSTMENT, "Solde corrigé",
                "Votre solde a été corrigé de ${money(i.netMinor)}. Nouveau solde : ${money(balance)}.",
                "Capital Invest: solde corrige de ${money(i.netMinor)}. $solde. $ref",
            )
            // Demande de retrait : l'app suit déjà son état, le client est prévenu au versement ou au remboursement.
            HistoryKind.WITHDRAWAL -> null
            // Le client vient de le faire lui-même dans l'app, et l'argent ne quitte pas son compte.
            HistoryKind.SAVINGS_DEPOSIT, HistoryKind.SAVINGS_WITHDRAWAL -> null
            // Change entre ses deux comptes : fait dans l'app, l'argent reste chez Capital Invest.
            HistoryKind.EXCHANGE -> null
            // Le client voit le résultat de l'achat dans l'app ; seul un remboursement arrive après coup.
            HistoryKind.AIRTIME -> null
            // Le paiement est notifié quand le facturier confirme (billPaid), avec le code du compteur.
            HistoryKind.BILL_PAYMENT -> null
            HistoryKind.BILL_REFUND -> Draft(
                NotificationKind.BILL_REFUNDED, "Facture non payée",
                "Votre paiement ${i.counterpartyName ?: ""} n'a pas abouti" + (i.failureMessage?.let { " : $it" } ?: "") +
                    ". ${money(i.amountMinor)} sont revenus sur votre compte.",
                "Capital Invest: paiement ${ascii(i.counterpartyName ?: "")} non abouti. ${money(i.amountMinor)} rendus. $solde. $ref",
            )
            HistoryKind.AIRTIME_REFUND -> Draft(
                NotificationKind.AIRTIME_REFUNDED, "Crédit non envoyé",
                "Le crédit pour ${i.msisdn ?: "ce numéro"} n'a pas pu être envoyé" + (i.failureMessage?.let { " : $it" } ?: "") +
                    ". ${money(i.amountMinor)} sont revenus sur votre compte.",
                "Capital Invest: credit non envoye a ${i.msisdn ?: ""}. ${money(i.amountMinor)} rendus. $solde. $ref",
            )
        }
    }

    private fun queue(customerId: UUID, dedupeKey: String, d: Draft, transactionId: UUID?) =
        insert(customerId, null, null, dedupeKey, d, transactionId)

    /** Le marchand n'a pas d'app : seul le SMS compte, la ligne sert de file d'envoi et de trace. */
    private fun queueForMerchant(merchantId: UUID, dedupeKey: String, d: Draft, transactionId: UUID?) =
        insert(null, merchantId, null, dedupeKey, d, transactionId)

    private fun insert(customerId: UUID?, merchantId: UUID?, phone: String?, dedupeKey: String, d: Draft, transactionId: UUID?) {
        val sms = d.sms?.let { ascii(it).take(160) }
        val inserted = jdbc.sql(
            """INSERT INTO notification (id, customer_id, merchant_id, phone, dedupe_key, kind, title, body, transaction_id, sms_text, sms_status, next_attempt_at, created_at)
               VALUES (:id, :customer, :merchant, :phone, :key, :kind, :title, :body, :tx, :sms, :status, :next, :now)
               ON CONFLICT (dedupe_key) DO NOTHING""",
        ).param("id", UUID.randomUUID()).param("customer", customerId).param("merchant", merchantId).param("phone", phone).param("key", dedupeKey).param("kind", d.kind.name)
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
            """SELECT n.id, n.sms_text, n.sms_attempts, COALESCE(c.phone, m.phone, n.phone) AS phone FROM notification n
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

    @GetMapping("/low-balance")
    fun lowBalance(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = notifications.lowBalanceSetting(customerId)

    /** [LowBalanceSetting.thresholdMinor] null : plus d'alerte. */
    @PutMapping("/low-balance")
    fun setLowBalance(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestBody b: LowBalanceSetting) =
        notifications.setLowBalance(customerId, b.thresholdMinor)

    /** Corps vide ou sans « ids » : tout est marqué comme lu. */
    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestBody(required = false) body: MarkReadRequest?) =
        notifications.markRead(customerId, body?.ids)
}
