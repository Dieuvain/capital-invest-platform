package cd.capitalinvest.platform.limits

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.exchange.ExchangeRates
import cd.capitalinvest.platform.ledger.LedgerRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Plafonds d'un niveau KYC, en centimes de dollar. */
data class KycTier(
    val perTransactionMinor: Long,
    val dailyOutMinor: Long,
    val maxBalanceMinor: Long,
)

/**
 * Plafonds par niveau KYC. Les valeurs par défaut sont provisoires :
 * elles doivent être validées par la conformité avant la mise en production.
 */
@ConfigurationProperties("capital-invest.limits")
data class LimitsProperties(
    val timezone: String = "Africa/Lubumbashi",
    val pinResetCooldownHours: Long = 24,
    /** Délai avant qu'une hausse du plafond personnel prenne effet. */
    val personalLimitRaiseDelayHours: Long = 24,
    /** Plus petit plafond journalier qu'un client peut choisir (10 $). */
    val personalLimitMinMinor: Long = 1_000,
    val tiers: Map<Int, KycTier> = mapOf(
        1 to KycTier(perTransactionMinor = 20_000, dailyOutMinor = 60_000, maxBalanceMinor = 100_000),
        2 to KycTier(perTransactionMinor = 400_000, dailyOutMinor = 1_000_000, maxBalanceMinor = 2_000_000),
    ),
)

/** Hausse du plafond personnel en attente. [dailyOutMinor] null : retour au plafond du niveau. */
data class PendingDailyLimit(val dailyOutMinor: Long?, val effectiveAt: OffsetDateTime)

/** Plafond personnel du client à un instant donné. */
data class PersonalDailyLimit(val dailyOutMinor: Long?, val pending: PendingDailyLimit?)

data class LimitsView(
    val kycLevel: Int,
    val perTransactionMinor: Long,
    /** Plafond journalier qui s'applique : celui du niveau, ou le plafond personnel s'il est plus bas. */
    val dailyOutMinor: Long,
    val dailyOutUsedMinor: Long,
    val dailyOutRemainingMinor: Long,
    val maxBalanceMinor: Long,
    val outgoingBlockedUntil: OffsetDateTime?,
    /** Plafond journalier du niveau KYC. */
    val levelDailyOutMinor: Long = dailyOutMinor,
    /** Plafond choisi par le client ; null : celui du niveau. */
    val personalDailyOutMinor: Long? = null,
    val pendingDailyOut: PendingDailyLimit? = null,
    val personalMinMinor: Long = 0,
)

class LimitExceededException(code: String, message: String) : BusinessException(code, message)

/**
 * Contrôle des plafonds. Les appelants doivent avoir verrouillé le portefeuille concerné
 * (SELECT … FOR UPDATE) dans la même transaction, sinon deux opérations simultanées
 * pourraient passer chacune sous le plafond et le dépasser ensemble.
 */
@Service
class LimitsService(
    private val props: LimitsProperties,
    private val jdbc: JdbcClient,
    private val ledger: LedgerRepository,
    private val clock: Clock,
    private val rates: ExchangeRates,
) {
    private val zone = ZoneId.of(props.timezone)

    fun tier(customer: Customer): KycTier =
        props.tiers[customer.kycLevel] ?: error("Aucun plafond configuré pour le niveau KYC ${customer.kycLevel}")

    /** Envoi d'argent, retrait ou paiement marchand : délai après reset du PIN, plafond par opération, plafond journalier. */
    fun checkOutgoing(customer: Customer, walletId: UUID, amountMinor: Long) {
        outgoingBlockedUntil(customer.id)?.let {
            throw BusinessException(
                "PIN_RECENTLY_RESET",
                "Par sécurité, les envois sont suspendus pendant ${props.pinResetCooldownHours} h après un changement de PIN",
                HttpStatus.FORBIDDEN,
            )
        }
        val tier = tier(customer)
        if (amountMinor > tier.perTransactionMinor) {
            throw LimitExceededException("LIMIT_PER_TRANSACTION", "Montant supérieur au plafond par opération de votre niveau")
        }
        val personal = personalLimit(customer.id).dailyOutMinor
        val used = dailyOutUsed(walletId)
        if (personal != null && personal < tier.dailyOutMinor && used + amountMinor > personal) {
            throw LimitExceededException("LIMIT_DAILY_PERSONAL", "Plafond journalier que vous avez choisi atteint")
        }
        if (used + amountMinor > tier.dailyOutMinor) {
            throw LimitExceededException("LIMIT_DAILY", "Plafond journalier d'envoi atteint")
        }
    }

    /** Recharge : plafond par opération et solde maximal, en comptant les recharges encore en attente. */
    fun checkTopup(customer: Customer, walletId: UUID, amountMinor: Long) {
        val tier = tier(customer)
        if (amountMinor > tier.perTransactionMinor) {
            throw LimitExceededException("LIMIT_PER_TRANSACTION", "Montant supérieur au plafond par opération de votre niveau")
        }
        if (holdings(customer.id, walletId) + pendingTopups(customer.id) + amountMinor > tier.maxBalanceMinor) {
            throw LimitExceededException("LIMIT_MAX_BALANCE", "Cette recharge dépasserait le solde maximal de votre niveau")
        }
    }

    /** Réception d'un envoi : le solde du destinataire ne doit pas dépasser son plafond. */
    fun checkIncoming(recipient: Customer, walletId: UUID, amountMinor: Long) {
        if (holdings(recipient.id, walletId) + pendingTopups(recipient.id) + amountMinor > tier(recipient).maxBalanceMinor) {
            // On ne révèle pas le solde du destinataire.
            throw LimitExceededException("RECIPIENT_LIMIT_REACHED", "Le destinataire ne peut pas recevoir ce montant")
        }
    }

    fun view(customer: Customer, walletId: UUID): LimitsView {
        val tier = tier(customer)
        val used = dailyOutUsed(walletId)
        val personal = personalLimit(customer.id)
        val daily = minOf(tier.dailyOutMinor, personal.dailyOutMinor ?: Long.MAX_VALUE)
        return LimitsView(
            kycLevel = customer.kycLevel,
            perTransactionMinor = tier.perTransactionMinor,
            dailyOutMinor = daily,
            dailyOutUsedMinor = used,
            dailyOutRemainingMinor = (daily - used).coerceAtLeast(0),
            maxBalanceMinor = tier.maxBalanceMinor,
            outgoingBlockedUntil = outgoingBlockedUntil(customer.id),
            levelDailyOutMinor = tier.dailyOutMinor,
            personalDailyOutMinor = personal.dailyOutMinor,
            pendingDailyOut = personal.pending,
            personalMinMinor = props.personalLimitMinMinor,
        )
    }

    /**
     * Plafond personnel en vigueur maintenant. Une hausse arrivée à échéance s'applique ici, à la lecture :
     * pas besoin de tâche planifiée.
     */
    fun personalLimit(customerId: UUID): PersonalDailyLimit {
        val now = OffsetDateTime.now(clock)
        return jdbc.sql("SELECT daily_limit_minor, pending_daily_limit_minor, pending_daily_limit_at FROM customer WHERE id = :id")
            .param("id", customerId)
            .query { rs, _ ->
                val current = rs.getObject("daily_limit_minor") as Long?
                val pendingValue = rs.getObject("pending_daily_limit_minor") as Long?
                val pendingAt = rs.getObject("pending_daily_limit_at", OffsetDateTime::class.java)
                when {
                    pendingAt == null -> PersonalDailyLimit(current, null)
                    !now.isBefore(pendingAt) -> PersonalDailyLimit(pendingValue, null)
                    else -> PersonalDailyLimit(current, PendingDailyLimit(pendingValue, pendingAt))
                }
            }.list().firstOrNull() ?: PersonalDailyLimit(null, null)
    }

    /** Total envoyé depuis minuit, heure de Lubumbashi. */
    fun dailyOutUsed(walletId: UUID): Long {
        val dayStart = clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime()
        return jdbc.sql(
            // Montants reçus par d'autres clients dans les envois de ce portefeuille : les frais ne comptent pas.
            """SELECT COALESCE(SUM(c.amount_minor), 0)
               FROM ledger_transaction t
               JOIN ledger_entry c ON c.transaction_id = t.id AND c.direction = 'CREDIT'
               JOIN ledger_account a ON a.id = c.account_id AND a.type = 'CUSTOMER_WALLET'
               WHERE t.type = 'TRANSFER'
                 AND t.id IN (SELECT d.transaction_id FROM ledger_entry d
                              WHERE d.account_id = :w AND d.direction = 'DEBIT' AND d.created_at >= :since)""",
        ).param("w", walletId).param("since", dayStart).query(Long::class.java).single() +
            withdrawnSince(walletId, dayStart) + paidToMerchantsSince(walletId, dayStart) + airtimeSince(walletId, dayStart) + billsSince(walletId, dayStart) +
            setAsideSince(walletId, dayStart)
    }

    /**
     * Argent parti vers un compte d'attente : envoi à un proche sans compte, participation à une cagnotte.
     * Sans les frais ; un envoi annulé ensuite compte quand même, comme un envoi normal.
     */
    private fun setAsideSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(c.amount_minor), 0) FROM ledger_entry c
               JOIN ledger_account a ON a.id = c.account_id AND a.type IN ('PENDING_TRANSFERS', 'CUSTOMER_POT')
               WHERE c.direction = 'CREDIT'
                 AND c.transaction_id IN (SELECT d.transaction_id FROM ledger_entry d
                                          WHERE d.account_id = :w AND d.direction = 'DEBIT' AND d.created_at >= :since)""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

    /** Paiements chez les marchands du jour : ce que le client a payé (il n'y a pas de frais). */
    private fun paidToMerchantsSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(d.amount_minor), 0) FROM ledger_entry d
               JOIN ledger_transaction t ON t.id = d.transaction_id AND t.type = 'MERCHANT_PAYMENT'
               WHERE d.account_id = :w AND d.direction = 'DEBIT' AND d.created_at >= :since""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

    /** Crédit téléphone acheté dans la journée ; un achat refusé et remboursé ne compte pas. */
    private fun airtimeSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(p.amount_minor), 0) FROM airtime_purchase p
               JOIN ledger_account a ON a.customer_id = p.customer_id AND a.id = :w
               WHERE p.created_at >= :since AND p.status <> 'FAILED'""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

    /** Factures payées dans la journée ; un paiement refusé et remboursé ne compte pas. */
    private fun billsSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(p.amount_minor), 0) FROM bill_payment p
               JOIN ledger_account a ON a.customer_id = p.customer_id AND a.id = :w
               WHERE p.created_at >= :since AND p.status <> 'FAILED'""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

    /** Retraits vers mobile money du jour, sans les frais ; un retrait échoué et remboursé ne compte pas. */
    private fun withdrawnSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(w.amount_minor), 0) FROM withdrawal w
               JOIN ledger_account a ON a.customer_id = w.customer_id AND a.id = :w
               WHERE w.created_at >= :since AND w.status <> 'FAILED'""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

    /** Portefeuille plus épargne : mettre de l'argent de côté ne permet pas de dépasser le solde maximal du niveau. */
    private fun holdings(customerId: UUID, walletId: UUID): Long =
        ledger.balance(walletId) + jdbc.sql(
            """SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
               FROM ledger_account a JOIN ledger_entry e ON e.account_id = a.id
               WHERE a.customer_id = :c AND a.type = 'CUSTOMER_SAVINGS'""",
        ).param("c", customerId).query(Long::class.java).single() + cdfHoldings(customerId)

    /** Le compte CDF compte dans le solde maximal, à sa valeur en dollars au taux du jour. */
    private fun cdfHoldings(customerId: UUID): Long =
        ledger.findCustomerWallet(customerId, cd.capitalinvest.platform.common.Currency.CDF)
            ?.let { rates.cdfToUsdMinor(ledger.balance(it.id)) } ?: 0

    private fun pendingTopups(customerId: UUID): Long =
        jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM topup WHERE customer_id = :c AND status = 'PENDING'")
            .param("c", customerId).query(Long::class.java).single()

    private fun outgoingBlockedUntil(customerId: UUID): OffsetDateTime? {
        val resetAt = jdbc.sql("SELECT pin_reset_at FROM customer WHERE id = :id").param("id", customerId)
            .query { rs, _ -> rs.getObject("pin_reset_at", OffsetDateTime::class.java) }
            .list().firstOrNull() ?: return null
        val until = resetAt.plusHours(props.pinResetCooldownHours)
        return if (OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).isBefore(until)) until else null
    }
}
