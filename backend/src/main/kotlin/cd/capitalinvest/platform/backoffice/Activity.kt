package cd.capitalinvest.platform.backoffice

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.limits.LimitsProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Une famille d'opérations : nombre et montant (sans les frais), remboursements déduits. */
data class Flow(val count: Long, val amountMinor: Long)

/** Chiffres d'une journée, heure de Lubumbashi. */
data class ActivityDay(
    val date: LocalDate,
    val newCustomers: Long,
    /** Clients dont le portefeuille a bougé ce jour-là. */
    val activeCustomers: Long,
    val topups: Flow,
    val transfers: Flow,
    val withdrawals: Flow,
    val merchantPayments: Flow,
    /** Factures et crédit téléphone. */
    val billsAndAirtime: Flow,
    /** Frais et commissions encaissés, remboursements de frais déduits. */
    val feesMinor: Long,
    /** Envois à un proche sans compte : partis, reçus par le proche à l'ouverture de son compte, revenus à l'expéditeur. */
    val invitesSent: Flow,
    val invitesClaimed: Flow,
    val invitesRefunded: Flow,
)

data class ActivityTotals(
    val newCustomers: Long,
    val activeCustomers: Long,
    val topups: Flow,
    val transfers: Flow,
    val withdrawals: Flow,
    val merchantPayments: Flow,
    val billsAndAirtime: Flow,
    val feesMinor: Long,
    val invitesSent: Flow,
    val invitesClaimed: Flow,
    val invitesRefunded: Flow,
)

data class Activity(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<ActivityDay>,
    val totals: ActivityTotals,
    /** Mêmes chiffres sur la période précédente de même durée, pour comparer. */
    val previous: ActivityTotals,
    /** Aujourd'hui : clients actifs (ni suspendus ni fermés), et argent qu'ils ont chez Capital Invest. */
    val customersActive: Long,
    val customerHoldingsMinor: Long,
    /** Aujourd'hui : envois qui attendent qu'un proche ouvre son compte. */
    val pendingInvites: Flow,
)

/**
 * Activité de la plateforme pour la direction et la conformité : tout est recalculé à partir du grand
 * livre, sans table de statistiques à tenir à jour. Les remboursements (retrait, facture, crédit non
 * servis) viennent en déduction du montant de leur famille.
 */
@Service
class ActivityService(private val jdbc: JdbcClient, private val limits: LimitsProperties, private val clock: Clock) {
    private val zone get() = ZoneId.of(limits.timezone)

    private class Row(val day: LocalDate, val type: String, val count: Long, val walletIn: Long, val walletOut: Long, val fees: Long)

    @Transactional(readOnly = true)
    fun activity(days: Int): Activity {
        if (days !in 1..90) throw BusinessException("INVALID_PERIOD", "Choisissez entre 1 et 90 jours")
        val to = LocalDate.now(clock.withZone(zone))
        val from = to.minusDays(days - 1L)
        val previousFrom = from.minusDays(days.toLong())
        val rows = rows(previousFrom, to)
        val newCustomers = perDay("SELECT (created_at AT TIME ZONE :tz)::date AS d, count(*) AS n FROM customer WHERE created_at >= :from AND created_at < :to GROUP BY 1", previousFrom, to)
        val active = perDay(
            """SELECT (t.created_at AT TIME ZONE :tz)::date AS d, count(DISTINCT a.customer_id) AS n
               FROM ledger_transaction t JOIN ledger_entry e ON e.transaction_id = t.id JOIN ledger_account a ON a.id = e.account_id
               WHERE a.type = 'CUSTOMER_WALLET' AND t.created_at >= :from AND t.created_at < :to GROUP BY 1""",
            previousFrom, to,
        )
        val list = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            day(d, rows.filter { it.day == d }, newCustomers[d] ?: 0, active[d] ?: 0)
        }.toList()
        val before = generateSequence(previousFrom) { it.plusDays(1) }.takeWhile { it.isBefore(from) }.map { d ->
            day(d, rows.filter { it.day == d }, newCustomers[d] ?: 0, active[d] ?: 0)
        }.toList()
        return Activity(
            from, to, list,
            totals(list, distinctActive(from, to)),
            totals(before, distinctActive(previousFrom, from.minusDays(1))),
            customersActive = jdbc.sql("SELECT count(*) FROM customer WHERE status = 'ACTIVE'").query(Long::class.java).single(),
            customerHoldingsMinor = jdbc.sql(
                """SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
                   FROM ledger_entry e JOIN ledger_account a ON a.id = e.account_id
                   WHERE a.type IN ('CUSTOMER_WALLET', 'CUSTOMER_SAVINGS') AND a.currency = 'USD'""",
            ).query(Long::class.java).single(),
            pendingInvites = jdbc.sql("SELECT count(*) AS n, COALESCE(SUM(amount_minor), 0) AS a FROM pending_transfer WHERE status = 'PENDING'")
                .query { rs, _ -> Flow(rs.getLong("n"), rs.getLong("a")) }.single(),
        )
    }

    private fun start(d: LocalDate) = d.atStartOfDay(zone).toOffsetDateTime()

    private fun rows(from: LocalDate, to: LocalDate): List<Row> = jdbc.sql(
        """SELECT (t.created_at AT TIME ZONE :tz)::date AS d, t.type, count(DISTINCT t.id) AS n,
                  COALESCE(SUM(CASE WHEN a.type = 'CUSTOMER_WALLET' AND e.direction = 'CREDIT' THEN e.amount_minor END), 0) AS wallet_in,
                  COALESCE(SUM(CASE WHEN a.type = 'CUSTOMER_WALLET' AND e.direction = 'DEBIT' THEN e.amount_minor END), 0) AS wallet_out,
                  COALESCE(SUM(CASE WHEN a.type = 'FEES_REVENUE' THEN CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END END), 0) AS fees
           FROM ledger_transaction t JOIN ledger_entry e ON e.transaction_id = t.id JOIN ledger_account a ON a.id = e.account_id
           WHERE t.created_at >= :from AND t.created_at < :to AND a.currency = 'USD'
           GROUP BY 1, 2""",
    ).param("tz", limits.timezone).param("from", start(from)).param("to", start(to.plusDays(1))).query { rs, _ ->
        Row(rs.getObject("d", LocalDate::class.java), rs.getString("type"), rs.getLong("n"), rs.getLong("wallet_in"), rs.getLong("wallet_out"), rs.getLong("fees"))
    }.list()

    private fun perDay(sql: String, from: LocalDate, to: LocalDate): Map<LocalDate, Long> =
        jdbc.sql(sql).param("tz", limits.timezone).param("from", start(from)).param("to", start(to.plusDays(1)))
            .query { rs, _ -> rs.getObject("d", LocalDate::class.java) to rs.getLong("n") }.list().toMap()

    /** Clients actifs sur toute la période : un client actif trois jours ne compte qu'une fois. */
    private fun distinctActive(from: LocalDate, to: LocalDate): Long = jdbc.sql(
        """SELECT count(DISTINCT a.customer_id) FROM ledger_transaction t JOIN ledger_entry e ON e.transaction_id = t.id
           JOIN ledger_account a ON a.id = e.account_id
           WHERE a.type = 'CUSTOMER_WALLET' AND t.created_at >= :from AND t.created_at < :to""",
    ).param("from", start(from)).param("to", start(to.plusDays(1))).query(Long::class.java).single()

    private fun day(d: LocalDate, rows: List<Row>, newCustomers: Long, active: Long): ActivityDay {
        fun flow(types: List<String>, refunds: List<String>, amount: (Row) -> Long): Flow {
            val out = rows.filter { it.type in types }
            val back = rows.filter { it.type in refunds }
            return Flow(out.sumOf { it.count }, out.sumOf(amount) - back.sumOf { it.walletIn + it.fees })
        }
        // Montant parti du portefeuille, frais déduits.
        val sent: (Row) -> Long = { it.walletOut - it.fees }
        return ActivityDay(
            date = d,
            newCustomers = newCustomers,
            activeCustomers = active,
            topups = flow(listOf("TOPUP"), emptyList()) { it.walletIn },
            // Entre clients : reçu par le destinataire ; vers un proche sans compte : parti de l'expéditeur, remboursements déduits.
            transfers = flow(listOf("TRANSFER", "TRANSFER_INVITE"), listOf("TRANSFER_INVITE_REFUND")) { if (it.type == "TRANSFER") it.walletIn else sent(it) },
            // Montant parti vers l'opérateur : débit du portefeuille moins les frais.
            withdrawals = flow(listOf("WITHDRAWAL"), listOf("WITHDRAWAL_SETTLED", "WITHDRAWAL_REFUND"), sent),
            merchantPayments = flow(listOf("MERCHANT_PAYMENT"), emptyList()) { it.walletOut },
            billsAndAirtime = flow(listOf("BILL_PAYMENT", "AIRTIME"), listOf("BILL_REFUND", "AIRTIME_REFUND"), sent),
            feesMinor = rows.sumOf { it.fees },
            invitesSent = flow(listOf("TRANSFER_INVITE"), emptyList(), sent),
            invitesClaimed = flow(listOf("TRANSFER_INVITE_CLAIM"), emptyList()) { it.walletIn },
            invitesRefunded = flow(listOf("TRANSFER_INVITE_REFUND"), emptyList()) { it.walletIn + it.fees },
        )
    }

    private fun totals(days: List<ActivityDay>, active: Long): ActivityTotals {
        fun sum(f: (ActivityDay) -> Flow) = Flow(days.sumOf { f(it).count }, days.sumOf { f(it).amountMinor })
        return ActivityTotals(
            days.sumOf { it.newCustomers }, active,
            sum { it.topups }, sum { it.transfers }, sum { it.withdrawals }, sum { it.merchantPayments }, sum { it.billsAndAirtime },
            days.sumOf { it.feesMinor },
            sum { it.invitesSent }, sum { it.invitesClaimed }, sum { it.invitesRefunded },
        )
    }
}

@RestController
@RequestMapping("/v1/backoffice")
class ActivityController(private val activity: ActivityService) {
    /** Direction, conformité et marketing : pas de données personnelles, seulement des totaux. */
    @GetMapping("/activity")
    fun activity(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestParam(defaultValue = "30") days: Int): Activity {
        if (me.role == AgentRole.KYC_AGENT) throw ForbiddenException("ACTIVITY_FORBIDDEN", "Réservé à la direction, à la conformité et au marketing")
        return activity.activity(days)
    }
}
