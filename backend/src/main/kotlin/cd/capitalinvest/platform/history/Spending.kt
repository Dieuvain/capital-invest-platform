package cd.capitalinvest.platform.history

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.limits.LimitsProperties
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/** Où part l'argent du client. L'épargne n'est pas une dépense : elle est comptée à part. */
enum class SpendingCategory { TRANSFERS, MERCHANTS, BILLS, AIRTIME, WITHDRAWALS, FEES }

data class CategorySpending(
    val category: SpendingCategory,
    val amountMinor: Long,
    /** Même catégorie sur la période de comparaison ; null sans période de comparaison. */
    val previousMinor: Long?,
    val count: Int,
)

/** Les personnes à qui le client envoie le plus, numéro masqué. */
data class TopRecipient(val name: String, val phone: String?, val amountMinor: Long, val count: Int)

data class SpendingSummary(
    val month: YearMonth,
    val partial: Boolean,
    val from: LocalDate,
    val to: LocalDate,
    /**
     * Période de comparaison : le mois d'avant, arrêté au même jour quand le mois est en cours
     * (le 19 novembre se compare au 1er–19 octobre). Null quand le compte n'était pas encore ouvert.
     */
    val comparedFrom: LocalDate?,
    val comparedTo: LocalDate?,
    /** Dépenses, frais compris, remboursements déduits. */
    val spentMinor: Long,
    val previousSpentMinor: Long?,
    /** Recharges et argent reçu d'autres clients. */
    val receivedMinor: Long,
    /** Mis de côté sur l'épargne, moins ce qui en a été repris. */
    val savedMinor: Long,
    /** De la plus grosse dépense à la plus petite ; les catégories vides sur les deux périodes sont omises. */
    val categories: List<CategorySpending>,
    val topRecipients: List<TopRecipient>,
)

/** « Mes dépenses » : un mois de l'historique regroupé par catégorie, comparé au mois d'avant. */
@Service
class SpendingService(private val statements: StatementService, private val limits: LimitsProperties) {
    private val zone get() = ZoneId.of(limits.timezone)

    fun summary(customerId: UUID, month: YearMonth): SpendingSummary {
        val s = statements.statement(customerId, month)
        val previousMonth = month.minusMonths(1)
        val previous = try {
            statements.statement(customerId, previousMonth)
        } catch (e: BusinessException) {
            if (e.code != "STATEMENT_UNAVAILABLE") throw e
            null
        }
        val comparedTo = previous?.let {
            if (s.partial) previousMonth.atDay(minOf(s.to.dayOfMonth, previousMonth.lengthOfMonth())) else previousMonth.atEndOfMonth()
        }
        val previousItems = previous?.items?.filter { !it.createdAt.atZoneSameInstant(zone).toLocalDate().isAfter(comparedTo) }
        return summarize(s.month, s.partial, s.from, s.to, s.items, previousItems, previous?.from, comparedTo)
    }

    companion object {
        private fun categoryOf(kind: HistoryKind): SpendingCategory? = when (kind) {
            HistoryKind.TRANSFER_SENT, HistoryKind.TRANSFER_REFUND -> SpendingCategory.TRANSFERS
            HistoryKind.MERCHANT_PAYMENT -> SpendingCategory.MERCHANTS
            HistoryKind.BILL_PAYMENT, HistoryKind.BILL_REFUND -> SpendingCategory.BILLS
            HistoryKind.AIRTIME, HistoryKind.AIRTIME_REFUND -> SpendingCategory.AIRTIME
            HistoryKind.WITHDRAWAL, HistoryKind.WITHDRAWAL_REFUND -> SpendingCategory.WITHDRAWALS
            else -> null
        }

        private fun counted(items: List<HistoryItem>) = items.filter { it.status != HistoryStatus.FAILED && it.netMinor != 0L }

        /** Montant par catégorie, sans les frais : un remboursement (montant positif) vient en déduction. */
        private fun byCategory(items: List<HistoryItem>): Map<SpendingCategory, Pair<Long, Int>> {
            val out = mutableMapOf<SpendingCategory, Pair<Long, Int>>()
            for (i in counted(items)) {
                val refund = i.netMinor > 0
                // Un remboursement rend aussi les frais : ils viennent en déduction des frais payés.
                val fee = if (refund) -i.feeMinor else i.feeMinor
                if (fee != 0L) out.merge(SpendingCategory.FEES, fee to 0) { a, b -> (a.first + b.first) to a.second }
                val c = categoryOf(i.kind) ?: continue
                out.merge(c, (-i.netMinor - fee) to (if (refund) 0 else 1)) { a, b -> (a.first + b.first) to (a.second + b.second) }
            }
            return out
        }

        fun summarize(
            month: YearMonth, partial: Boolean, from: LocalDate, to: LocalDate,
            items: List<HistoryItem>, previousItems: List<HistoryItem>?, comparedFrom: LocalDate?, comparedTo: LocalDate?,
        ): SpendingSummary {
            val now = byCategory(items)
            val before = previousItems?.let(::byCategory)
            val categories = SpendingCategory.entries
                .map { c -> CategorySpending(c, now[c]?.first ?: 0, before?.let { it[c]?.first ?: 0 }, now[c]?.second ?: 0) }
                .filter { it.amountMinor != 0L || (it.previousMinor ?: 0) != 0L }
                .sortedByDescending { it.amountMinor }
            val live = counted(items)
            val recipients = live.filter { it.kind == HistoryKind.TRANSFER_SENT }
                .groupBy { it.counterpartyPhone ?: it.counterpartyName }
                .map { (_, l) -> TopRecipient(l.first().counterpartyName ?: "Client", l.first().counterpartyPhone, l.sumOf { it.amountMinor }, l.size) }
                .sortedByDescending { it.amountMinor }
                .take(3)
            return SpendingSummary(
                month = month, partial = partial, from = from, to = to,
                comparedFrom = comparedFrom, comparedTo = comparedTo,
                spentMinor = categories.sumOf { it.amountMinor },
                previousSpentMinor = before?.values?.sumOf { it.first },
                receivedMinor = live.filter { it.kind == HistoryKind.TOPUP || it.kind == HistoryKind.TRANSFER_RECEIVED }.sumOf { it.netMinor },
                savedMinor = -live.filter { it.kind == HistoryKind.SAVINGS_DEPOSIT || it.kind == HistoryKind.SAVINGS_WITHDRAWAL }.sumOf { it.netMinor },
                categories = categories,
                topRecipients = recipients,
            )
        }
    }
}

@RestController
@RequestMapping("/v1/me/spending")
class SpendingController(private val spending: SpendingService, private val limits: LimitsProperties, private val clock: Clock) {

    /** [month] au format AAAA-MM ; le mois en cours par défaut. */
    @GetMapping
    fun summary(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestParam(required = false) month: String?): SpendingSummary {
        val m = if (month == null) YearMonth.now(clock.withZone(ZoneId.of(limits.timezone))) else try {
            YearMonth.parse(month)
        } catch (e: DateTimeParseException) {
            throw BusinessException("INVALID_MONTH", "Mois attendu au format AAAA-MM")
        }
        return spending.summary(customerId, m)
    }
}
