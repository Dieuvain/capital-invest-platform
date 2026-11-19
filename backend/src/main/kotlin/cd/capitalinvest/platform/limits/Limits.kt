package cd.capitalinvest.platform.limits

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.customers.Customer
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
    val tiers: Map<Int, KycTier> = mapOf(
        1 to KycTier(perTransactionMinor = 20_000, dailyOutMinor = 60_000, maxBalanceMinor = 100_000),
        2 to KycTier(perTransactionMinor = 400_000, dailyOutMinor = 1_000_000, maxBalanceMinor = 2_000_000),
    ),
)

data class LimitsView(
    val kycLevel: Int,
    val perTransactionMinor: Long,
    val dailyOutMinor: Long,
    val dailyOutUsedMinor: Long,
    val dailyOutRemainingMinor: Long,
    val maxBalanceMinor: Long,
    val outgoingBlockedUntil: OffsetDateTime?,
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
        if (dailyOutUsed(walletId) + amountMinor > tier.dailyOutMinor) {
            throw LimitExceededException("LIMIT_DAILY", "Plafond journalier d'envoi atteint")
        }
    }

    /** Recharge : plafond par opération et solde maximal, en comptant les recharges encore en attente. */
    fun checkTopup(customer: Customer, walletId: UUID, amountMinor: Long) {
        val tier = tier(customer)
        if (amountMinor > tier.perTransactionMinor) {
            throw LimitExceededException("LIMIT_PER_TRANSACTION", "Montant supérieur au plafond par opération de votre niveau")
        }
        if (ledger.balance(walletId) + pendingTopups(customer.id) + amountMinor > tier.maxBalanceMinor) {
            throw LimitExceededException("LIMIT_MAX_BALANCE", "Cette recharge dépasserait le solde maximal de votre niveau")
        }
    }

    /** Réception d'un envoi : le solde du destinataire ne doit pas dépasser son plafond. */
    fun checkIncoming(recipient: Customer, walletId: UUID, amountMinor: Long) {
        if (ledger.balance(walletId) + pendingTopups(recipient.id) + amountMinor > tier(recipient).maxBalanceMinor) {
            // On ne révèle pas le solde du destinataire.
            throw LimitExceededException("RECIPIENT_LIMIT_REACHED", "Le destinataire ne peut pas recevoir ce montant")
        }
    }

    fun view(customer: Customer, walletId: UUID): LimitsView {
        val tier = tier(customer)
        val used = dailyOutUsed(walletId)
        return LimitsView(
            kycLevel = customer.kycLevel,
            perTransactionMinor = tier.perTransactionMinor,
            dailyOutMinor = tier.dailyOutMinor,
            dailyOutUsedMinor = used,
            dailyOutRemainingMinor = (tier.dailyOutMinor - used).coerceAtLeast(0),
            maxBalanceMinor = tier.maxBalanceMinor,
            outgoingBlockedUntil = outgoingBlockedUntil(customer.id),
        )
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
            withdrawnSince(walletId, dayStart) + paidToMerchantsSince(walletId, dayStart)
    }

    /** Paiements chez les marchands du jour : ce que le client a payé (il n'y a pas de frais). */
    private fun paidToMerchantsSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(d.amount_minor), 0) FROM ledger_entry d
               JOIN ledger_transaction t ON t.id = d.transaction_id AND t.type = 'MERCHANT_PAYMENT'
               WHERE d.account_id = :w AND d.direction = 'DEBIT' AND d.created_at >= :since""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

    /** Retraits vers mobile money du jour, sans les frais ; un retrait échoué et remboursé ne compte pas. */
    private fun withdrawnSince(walletId: UUID, since: OffsetDateTime): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(w.amount_minor), 0) FROM withdrawal w
               JOIN ledger_account a ON a.customer_id = w.customer_id AND a.id = :w
               WHERE w.created_at >= :since AND w.status <> 'FAILED'""",
        ).param("w", walletId).param("since", since).query(Long::class.java).single()

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
