package cd.capitalinvest.platform.credit

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class PlannedInstalment(val number: Int, val dueDate: LocalDate, val principalMinor: Long, val interestMinor: Long) {
    val amountMinor get() = principalMinor + interestMinor
}

/**
 * Calculs du crédit, sans base de données : échéancier à mensualités constantes (intérêts sur le capital
 * restant dû) et taux effectif global. Tout est en centimes ; les intérêts d'un mois sont arrondis au centime le plus proche.
 */
object CreditMath {
    private val mc = MathContext.DECIMAL64

    private fun rate(monthlyRateBp: Int) = BigDecimal(monthlyRateBp).divide(BigDecimal(10_000), mc)

    /** Mensualité constante, arrondie au centime supérieur pour que la dernière échéance ne soit jamais plus grosse. */
    fun instalment(principalMinor: Long, months: Int, monthlyRateBp: Int): Long {
        require(principalMinor > 0 && months > 0)
        if (monthlyRateBp == 0) return (principalMinor + months - 1) / months
        val r = rate(monthlyRateBp)
        val factor = BigDecimal.ONE.subtract(BigDecimal.ONE.add(r).pow(-months, mc), mc)
        return BigDecimal(principalMinor).multiply(r, mc).divide(factor, mc).setScale(0, RoundingMode.CEILING).toLong()
    }

    /**
     * Échéancier : une échéance par mois, le même jour que [start] (ou le dernier jour du mois s'il est plus court).
     * [carriedInterestMinor] : intérêts d'échéances en retard repris dans la première échéance d'un rééchelonnement.
     */
    fun schedule(principalMinor: Long, months: Int, monthlyRateBp: Int, start: LocalDate, firstNumber: Int = 1, carriedInterestMinor: Long = 0): List<PlannedInstalment> {
        val payment = instalment(principalMinor, months, monthlyRateBp)
        val r = rate(monthlyRateBp)
        var remaining = principalMinor
        return (1..months).map { k ->
            val interest = BigDecimal(remaining).multiply(r, mc).setScale(0, RoundingMode.HALF_UP).toLong()
            val principal = if (k == months) remaining else (payment - interest).coerceIn(0, remaining)
            remaining -= principal
            PlannedInstalment(firstNumber + k - 1, start.plusMonths(k.toLong()), principal, interest + if (k == 1) carriedInterestMinor else 0)
        }
    }

    /** Taux effectif global annuel, en points de base : (1 + taux mensuel)^12 − 1. Sans frais de dossier pour l'instant. */
    fun aprBp(monthlyRateBp: Int): Int =
        BigDecimal.ONE.add(rate(monthlyRateBp)).pow(12, mc).subtract(BigDecimal.ONE).multiply(BigDecimal(10_000)).setScale(0, RoundingMode.HALF_UP).toInt()
}
