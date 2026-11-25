package cd.capitalinvest.platform.credit

import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.notifications.NotificationService.Companion.money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Un élément qui explique le score : ce qui aide (positive) ou ce qui freine, avec de quoi progresser. */
data class CreditFactor(val label: String, val positive: Boolean)

data class ScoreResult(
    val score: Int,
    val ceilingMinor: Long,
    val factors: List<CreditFactor>,
    /** Leviers pour obtenir une offre ou un plafond plus haut. */
    val levers: List<String>,
    val averageMonthlyInflowMinor: Long,
)

/**
 * Score du crédit express, sur 100, à partir de ce que Capital Invest voit déjà (activité 5.1 de l'analyse UML) :
 * ancienneté du compte, argent reçu (recharges, envois reçus, paie) sur 90 jours, régularité, remboursements passés.
 *
 * Grille PROVISOIRE et volontairement simple, en attendant le modèle entraîné prévu par le dossier d'analyse
 * (§10 : entraînement en Python, exécution par le serveur). Elle est lisible par le client : chaque point se gagne.
 */
@Component
class CreditScoring(private val jdbc: JdbcClient, private val clock: Clock) {
    companion object {
        /** Ce qui compte comme argent reçu : pas l'épargne reprise, ni les remboursements, ni le crédit lui-même. */
        val INFLOW_TYPES = listOf("TOPUP", "TRANSFER", "TRANSFER_INVITE_CLAIM", "POT_PAYOUT")
        const val WINDOW_DAYS = 90L
    }

    fun score(customer: Customer, walletId: UUID, settings: CreditSettings): ScoreResult {
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val createdAt = jdbc.sql("SELECT created_at FROM customer WHERE id = :id").param("id", customer.id)
            .query(OffsetDateTime::class.java).single()
        val ageDays = Duration.between(createdAt, now).toDays()

        // Entrées par tranche de 30 jours : 0 = les 30 derniers jours.
        val byWindow = LongArray(3)
        jdbc.sql(
            """SELECT e.amount_minor, t.created_at FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
               WHERE e.account_id = :w AND e.direction = 'CREDIT' AND t.type IN (:types) AND t.created_at > :since AND t.created_at <= :now""",
        ).param("w", walletId).param("types", INFLOW_TYPES).param("since", now.minusDays(WINDOW_DAYS)).param("now", now)
            .query { rs, _ -> rs.getLong(1) to rs.getObject(2, OffsetDateTime::class.java) }.list()
            .forEach { (amount, at) -> byWindow[(Duration.between(at, now).toDays() / 30).toInt().coerceIn(0, 2)] += amount }
        val average = byWindow.sum() / 3
        val activeMonths = byWindow.count { it > 0 }

        val history = jdbc.sql(
            """SELECT c.status, EXISTS (SELECT 1 FROM credit_instalment i WHERE i.credit_id = c.id AND i.late_notified) AS was_late
               FROM credit c WHERE c.customer_id = :c AND c.status IN ('REPAID', 'WRITTEN_OFF')""",
        ).param("c", customer.id).query { rs, _ -> rs.getString(1) to rs.getBoolean(2) }.list()
        val onTime = history.count { it.first == "REPAID" && !it.second }
        val late = history.count { it.first == "REPAID" && it.second }

        val factors = mutableListOf<CreditFactor>()
        val levers = mutableListOf<String>()
        var score = 0

        score += when {
            ageDays >= 180 -> 20
            ageDays >= 90 -> 15
            ageDays >= 30 -> 8
            else -> 0
        }
        factors += CreditFactor(if (ageDays >= 1) "Compte ouvert depuis $ageDays jours" else "Compte ouvert aujourd'hui", ageDays >= 30)
        if (ageDays < 90) levers += "Votre score monte avec l'ancienneté du compte (90 jours, puis 180 jours)."

        score += when {
            average >= 50_000 -> 35
            average >= 20_000 -> 25
            average >= 5_000 -> 15
            average > 0 -> 5
            else -> 0
        }
        factors += if (average > 0) {
            CreditFactor("${money(average)} reçus par mois en moyenne sur 3 mois", average >= 5_000)
        } else {
            CreditFactor("Aucune recharge ni aucun envoi reçu ces 3 derniers mois", false)
        }
        if (average < 50_000) levers += "Faites verser votre paie ou rechargez votre compte Capital Invest chaque mois."

        score += when (activeMonths) {
            3 -> 20
            2 -> 10
            else -> 0
        }
        factors += CreditFactor("De l'argent reçu $activeMonths mois sur 3", activeMonths >= 2)
        if (activeMonths < 3) levers += "Recevez de l'argent sur votre compte chaque mois, même un petit montant."

        score += (onTime * 10 + late * 5).coerceAtMost(25)
        when {
            onTime + late == 0 -> {}
            late == 0 -> factors += CreditFactor(if (onTime == 1) "Un crédit remboursé à l'heure" else "$onTime crédits remboursés à l'heure", true)
            else -> factors += CreditFactor(if (late == 1) "Un crédit remboursé avec du retard" else "$late crédits remboursés avec du retard", false)
        }
        if (onTime + late > 0 || score >= settings.minScore) levers += "Chaque crédit remboursé à l'heure augmente votre plafond."

        // Plafond : une part des entrées du mois, plus 25 % par crédit remboursé à l'heure (jusqu'à 4), arrondie à 10 $ près.
        val raw = average * settings.ceilingPercentOfInflow / 100 * (100 + 25 * onTime.coerceAtMost(4)) / 100
        val ceiling = (raw / 1_000 * 1_000).coerceAtMost(settings.maxAmountMinor)
        return ScoreResult(score.coerceIn(0, 100), ceiling, factors, levers, average)
    }
}
