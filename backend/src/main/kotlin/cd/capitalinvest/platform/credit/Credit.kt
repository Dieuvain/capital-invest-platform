package cd.capitalinvest.platform.credit

import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitExceededException
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.notifications.NotificationService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Réglages du crédit, modifiables depuis le back-office (table credit_settings). */
data class CreditSettings(
    val minScore: Int,
    val reviewThresholdMinor: Long,
    val minAmountMinor: Long,
    val maxAmountMinor: Long,
    val monthlyRateBp: Int,
    val durationsMonths: List<Int>,
    val ceilingPercentOfInflow: Int,
    val defaultAfterDays: Int,
    val updatedAt: OffsetDateTime? = null,
)

/** Diagramme d'états 4.3 de l'analyse UML (l'offre, calculée à la demande, n'est pas stockée). */
enum class CreditStatus { PENDING_REVIEW, REJECTED, ACTIVE, LATE, DEFAULTED, WRITTEN_OFF, REPAID }

enum class InstalmentStatus { UPCOMING, LATE, PAID, CANCELLED }

data class Instalment(
    val number: Int,
    val dueDate: LocalDate,
    val amountMinor: Long,
    val principalMinor: Long,
    val interestMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long,
    val status: InstalmentStatus,
)

data class CreditView(
    val id: UUID,
    val status: CreditStatus,
    val principalMinor: Long,
    val durationMonths: Int,
    val monthlyRateBp: Int,
    /** Taux effectif global annuel, en points de base. */
    val aprBp: Int,
    val totalInterestMinor: Long,
    val totalPaidMinor: Long,
    /** Capital pas encore remboursé. */
    val outstandingPrincipalMinor: Long,
    /** À payer aujourd'hui : échéances arrivées et pas encore payées. */
    val dueNowMinor: Long,
    /** Pour tout rembourser aujourd'hui : ce qui est dû, plus le capital restant (les intérêts des mois à venir ne sont pas dus). */
    val payoffMinor: Long,
    val nextInstalment: Instalment?,
    /** Jours de retard de la plus vieille échéance impayée. */
    val daysLate: Int,
    val instalments: List<Instalment>,
    val score: Int,
    val decisionNote: String?,
    val rescheduleCount: Int,
    val createdAt: OffsetDateTime,
    val disbursedAt: OffsetDateTime?,
    val closedAt: OffsetDateTime?,
)

data class CreditOffer(
    val score: Int,
    val ceilingMinor: Long,
    val minAmountMinor: Long,
    val durationsMonths: List<Int>,
    val monthlyRateBp: Int,
    val aprBp: Int,
    /** Au-dessus de ce montant, un agent de crédit examine la demande avant le versement. */
    val reviewThresholdMinor: Long,
    val factors: List<CreditFactor>,
    val levers: List<String>,
)

/** Pourquoi il n'y a pas d'offre, et ce que le client peut faire. */
data class CreditRefusal(val code: String, val message: String, val score: Int?, val factors: List<CreditFactor>, val levers: List<String>)

data class CreditOverview(
    val offer: CreditOffer?,
    val refusal: CreditRefusal?,
    /** Crédit en cours ou demande en examen. */
    val current: CreditView?,
    /** Crédits terminés ou refusés, du plus récent au plus ancien. */
    val past: List<CreditView>,
)

data class CreditQuote(
    val amountMinor: Long,
    val durationMonths: Int,
    val monthlyRateBp: Int,
    val aprBp: Int,
    val instalmentMinor: Long,
    val totalInterestMinor: Long,
    val totalDueMinor: Long,
    val needsReview: Boolean,
    val schedule: List<PlannedInstalment>,
)

enum class RepaymentMode { DUE, FULL }

/** Un crédit avec son client, pour le back-office. */
data class BackofficeCredit(
    val credit: CreditView,
    val customerId: UUID,
    val customerName: String,
    val customerPhone: String,
    val kycLevel: Int,
    val walletBalanceMinor: Long,
    val ceilingMinor: Long,
    val factors: List<CreditFactor>,
    val decidedBy: String?,
    val decidedAt: OffsetDateTime?,
)

/**
 * Crédit express (séquence 3.4, états 4.3, activité 5.1 de l'analyse UML).
 *
 * - Il faut le KYC 2, un compte actif et aucun crédit en cours (un seul à la fois, demande en examen comprise).
 * - Le score et le plafond se calculent à chaque demande ; au-dessus du seuil de revue, un agent de crédit décide.
 * - Le versement crédite le portefeuille (donc la carte) depuis la créance sur les clients.
 * - Chaque échéance est prélevée sur le portefeuille, en partie si le solde ne suffit pas ;
 *   sinon le crédit passe en retard (SMS), puis en défaut après le délai des réglages.
 * - Les intérêts ne sont comptés en revenus qu'une fois encaissés ; un remboursement anticipé ne paie pas les intérêts à venir.
 */
@Service
class CreditService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val limitsProps: LimitsProperties,
    private val scoring: CreditScoring,
    private val notifications: NotificationService,
    private val audit: AuditLog,
    private val tx: TransactionTemplate,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    companion object {
        val RECEIVABLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000801")
        val INTEREST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000802")
        val LOSSES: UUID = UUID.fromString("00000000-0000-0000-0000-000000000803")
        val OPEN = listOf(CreditStatus.PENDING_REVIEW, CreditStatus.ACTIVE, CreditStatus.LATE, CreditStatus.DEFAULTED)
        val RUNNING = listOf(CreditStatus.ACTIVE, CreditStatus.LATE, CreditStatus.DEFAULTED)
        const val REMINDER_DAYS = 2L
    }

    private val log = LoggerFactory.getLogger(CreditService::class.java)
    private val zone = ZoneId.of(limitsProps.timezone)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
    private fun today(): LocalDate = clock.instant().atZone(zone).toLocalDate()

    // ---------- Réglages ----------

    fun settings(): CreditSettings = jdbc.sql("SELECT * FROM credit_settings WHERE id = 1").query { rs, _ ->
        CreditSettings(
            rs.getInt("min_score"), rs.getLong("review_threshold_minor"), rs.getLong("min_amount_minor"), rs.getLong("max_amount_minor"),
            rs.getInt("monthly_rate_bp"), (rs.getArray("durations_months").array as Array<*>).map { (it as Number).toInt() }.sorted(),
            rs.getInt("ceiling_percent_of_inflow"), rs.getInt("default_after_days"), rs.getObject("updated_at", OffsetDateTime::class.java),
        )
    }.single()

    fun updateSettings(agentId: UUID, s: CreditSettings): CreditSettings {
        val durations = s.durationsMonths.distinct().sorted()
        if (durations.isEmpty() || durations.any { it !in 1..24 }) throw BusinessException("INVALID_SETTINGS", "Proposez au moins une durée, de 1 à 24 mois")
        if (s.minScore !in 0..100) throw BusinessException("INVALID_SETTINGS", "Le score minimal va de 0 à 100")
        if (s.minAmountMinor <= 0 || s.maxAmountMinor < s.minAmountMinor) throw BusinessException("INVALID_SETTINGS", "Le montant maximal doit être au moins égal au minimal")
        if (s.reviewThresholdMinor <= 0) throw BusinessException("INVALID_SETTINGS", "Le seuil de revue doit être positif")
        if (s.monthlyRateBp !in 0..1000) throw BusinessException("INVALID_SETTINGS", "Le taux mensuel va de 0 à 10 %")
        if (s.ceilingPercentOfInflow !in 1..200) throw BusinessException("INVALID_SETTINGS", "La part des entrées va de 1 à 200 %")
        if (s.defaultAfterDays !in 1..365) throw BusinessException("INVALID_SETTINGS", "Le délai avant défaut va de 1 à 365 jours")
        tx.executeWithoutResult {
            jdbc.sql(
                """UPDATE credit_settings SET min_score = :score, review_threshold_minor = :review, min_amount_minor = :min, max_amount_minor = :max,
                   monthly_rate_bp = :rate, durations_months = CAST(:durations AS int[]), ceiling_percent_of_inflow = :pct, default_after_days = :days,
                   updated_at = :now, updated_by = :agent WHERE id = 1""",
            ).param("score", s.minScore).param("review", s.reviewThresholdMinor).param("min", s.minAmountMinor).param("max", s.maxAmountMinor)
                .param("rate", s.monthlyRateBp).param("durations", "{${durations.joinToString(",")}}").param("pct", s.ceilingPercentOfInflow)
                .param("days", s.defaultAfterDays).param("now", now()).param("agent", agentId).update()
            audit.record(
                ActorType.AGENT, agentId, "CREDIT_SETTINGS_CHANGED", "CREDIT_SETTINGS", UUID(0, 1),
                "score ${s.minScore} · revue ${s.reviewThresholdMinor} · ${s.minAmountMinor}-${s.maxAmountMinor} · taux ${s.monthlyRateBp} · durées $durations",
            )
        }
        return settings()
    }

    // ---------- Lecture ----------

    internal class Row(
        val id: UUID, val customerId: UUID, val status: CreditStatus, val principalMinor: Long, val durationMonths: Int,
        val monthlyRateBp: Int, val score: Int, val ceilingMinor: Long, val factors: String, val decidedBy: UUID?,
        val decisionNote: String?, val rescheduleCount: Int, val createdAt: OffsetDateTime, val disbursedAt: OffsetDateTime?,
        val decidedAt: OffsetDateTime?, val closedAt: OffsetDateTime?,
    )

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Row(
        rs.getObject("id", UUID::class.java), rs.getObject("customer_id", UUID::class.java), CreditStatus.valueOf(rs.getString("status")),
        rs.getLong("principal_minor"), rs.getInt("duration_months"), rs.getInt("monthly_rate_bp"), rs.getInt("score"),
        rs.getLong("ceiling_minor"), rs.getString("factors"), rs.getObject("decided_by", UUID::class.java), rs.getString("decision_note"),
        rs.getInt("reschedule_count"), rs.getObject("created_at", OffsetDateTime::class.java),
        rs.getObject("disbursed_at", OffsetDateTime::class.java), rs.getObject("decided_at", OffsetDateTime::class.java),
        rs.getObject("closed_at", OffsetDateTime::class.java),
    )

    private fun row(id: UUID, lock: Boolean = false): Row? =
        jdbc.sql("SELECT * FROM credit WHERE id = :id${if (lock) " FOR UPDATE" else ""}").param("id", id).query(::map).optional().orElse(null)

    private fun owned(customerId: UUID, id: UUID, lock: Boolean = false): Row =
        row(id, lock)?.takeIf { it.customerId == customerId } ?: throw NotFoundException("CREDIT_NOT_FOUND", "Crédit introuvable")

    private class InstalmentRow(
        val id: UUID, val number: Int, val dueDate: LocalDate, val principalMinor: Long, val interestMinor: Long,
        val paidMinor: Long, val status: InstalmentStatus, val reminderSent: Boolean, val lateNotified: Boolean,
    ) {
        val amountMinor get() = principalMinor + interestMinor
        val remainingMinor get() = amountMinor - paidMinor
        val open get() = status == InstalmentStatus.UPCOMING || status == InstalmentStatus.LATE
        /** Ce qui reste de capital : les intérêts sont payés en premier. */
        val principalRemainingMinor get() = principalMinor - (paidMinor - interestMinor).coerceAtLeast(0)
    }

    private fun instalments(creditId: UUID): List<InstalmentRow> =
        jdbc.sql("SELECT * FROM credit_instalment WHERE credit_id = :c ORDER BY number").param("c", creditId).query { rs, _ ->
            InstalmentRow(
                rs.getObject("id", UUID::class.java), rs.getInt("number"), rs.getObject("due_date", LocalDate::class.java),
                rs.getLong("principal_minor"), rs.getLong("interest_minor"), rs.getLong("paid_minor"),
                InstalmentStatus.valueOf(rs.getString("status")), rs.getBoolean("reminder_sent"), rs.getBoolean("late_notified"),
            )
        }.list()

    private fun view(r: Row): CreditView {
        val all = instalments(r.id)
        val today = today()
        val open = all.filter { it.open }
        val due = open.filter { !it.dueDate.isAfter(today) }
        val shown = all.filter { it.status != InstalmentStatus.CANCELLED || it.paidMinor > 0 }
        fun Instalment(i: InstalmentRow) = Instalment(i.number, i.dueDate, i.amountMinor, i.principalMinor, i.interestMinor, i.paidMinor, i.remainingMinor, i.status)
        return CreditView(
            id = r.id, status = r.status, principalMinor = r.principalMinor, durationMonths = r.durationMonths, monthlyRateBp = r.monthlyRateBp,
            aprBp = CreditMath.aprBp(r.monthlyRateBp),
            totalInterestMinor = if (all.isEmpty()) {
                CreditMath.schedule(r.principalMinor, r.durationMonths, r.monthlyRateBp, today).sumOf { it.interestMinor }
            } else {
                all.filter { it.status != InstalmentStatus.CANCELLED || it.paidMinor > 0 }.sumOf { it.interestMinor }
            },
            totalPaidMinor = all.sumOf { it.paidMinor },
            outstandingPrincipalMinor = open.sumOf { it.principalRemainingMinor },
            dueNowMinor = due.sumOf { it.remainingMinor },
            payoffMinor = due.sumOf { it.remainingMinor } + open.filter { it.dueDate.isAfter(today) }.sumOf { it.principalRemainingMinor },
            nextInstalment = open.firstOrNull()?.let(::Instalment),
            daysLate = due.firstOrNull()?.let { ChronoUnit.DAYS.between(it.dueDate, today).toInt() } ?: 0,
            instalments = shown.map(::Instalment),
            score = r.score, decisionNote = r.decisionNote, rescheduleCount = r.rescheduleCount,
            createdAt = r.createdAt, disbursedAt = r.disbursedAt, closedAt = r.closedAt,
        )
    }

    private fun customer(id: UUID): Customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    private fun wallet(customerId: UUID) = ledgerRepository.findCustomerWallet(customerId, Currency.USD)
        ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    private fun openCredit(customerId: UUID): Row? =
        jdbc.sql("SELECT * FROM credit WHERE customer_id = :c AND status IN (:open)").param("c", customerId)
            .param("open", OPEN.map { it.name }).query(::map).optional().orElse(null)

    fun overview(customerId: UUID): CreditOverview {
        val c = customer(customerId)
        val current = openCredit(customerId)
        val past = jdbc.sql("SELECT * FROM credit WHERE customer_id = :c AND status NOT IN (:open) ORDER BY created_at DESC LIMIT 10")
            .param("c", customerId).param("open", OPEN.map { it.name }).query(::map).list().map(::view)
        if (current != null) return CreditOverview(null, null, view(current), past)
        val s = settings()
        val (offer, refusal) = evaluate(c, s)
        return CreditOverview(offer, refusal, null, past)
    }

    /** Activité 5.1 : niveau KYC, crédit déjà en cours ou passé en perte, score, plafond. */
    private fun evaluate(c: Customer, s: CreditSettings): Pair<CreditOffer?, CreditRefusal?> {
        if (c.status != "ACTIVE") return null to CreditRefusal("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu ou fermé.", null, emptyList(), emptyList())
        if (c.kycLevel < 2) {
            return null to CreditRefusal(
                "KYC_LEVEL_REQUIRED", "Le crédit express demande le niveau 2.", null, emptyList(),
                listOf("Envoyez votre pièce d'identité depuis le Profil pour passer au niveau 2."),
            )
        }
        val writtenOff = jdbc.sql("SELECT count(*) FROM credit WHERE customer_id = :c AND status = 'WRITTEN_OFF'").param("c", c.id).query(Int::class.java).single()
        if (writtenOff > 0) {
            return null to CreditRefusal(
                "CREDIT_WRITTEN_OFF", "Un crédit précédent n'a pas été remboursé.", null, emptyList(),
                listOf("Passez à l'agence Capital Invest pour en parler avec un agent de crédit."),
            )
        }
        val r = scoring.score(c, wallet(c.id).id, s)
        if (r.score < s.minScore) {
            return null to CreditRefusal("SCORE_TOO_LOW", "Votre score (${r.score} sur 100) ne permet pas encore de crédit.", r.score, r.factors, r.levers)
        }
        if (r.ceilingMinor < s.minAmountMinor) {
            return null to CreditRefusal(
                "CEILING_TOO_LOW", "L'argent reçu sur votre compte ne permet pas encore le plus petit crédit (${NotificationService.money(s.minAmountMinor)}).",
                r.score, r.factors, r.levers,
            )
        }
        return CreditOffer(
            r.score, r.ceilingMinor, s.minAmountMinor, s.durationsMonths, s.monthlyRateBp, CreditMath.aprBp(s.monthlyRateBp),
            s.reviewThresholdMinor, r.factors, r.levers,
        ) to null
    }

    private fun requireOffer(c: Customer, s: CreditSettings, amountMinor: Long, durationMonths: Int): CreditOffer {
        openCredit(c.id)?.let {
            throw ConflictException("CREDIT_ALREADY_OPEN", if (it.status == CreditStatus.PENDING_REVIEW) "Votre demande de crédit est en cours d'examen" else "Vous avez déjà un crédit en cours")
        }
        val (offer, refusal) = evaluate(c, s)
        if (offer == null) {
            throw BusinessException(refusal!!.code, refusal.message, if (refusal.code == "KYC_LEVEL_REQUIRED") HttpStatus.FORBIDDEN else HttpStatus.UNPROCESSABLE_ENTITY)
        }
        if (durationMonths !in offer.durationsMonths) {
            throw BusinessException("INVALID_DURATION", "Choisissez une durée de ${offer.durationsMonths.joinToString(", ")} mois")
        }
        if (amountMinor < offer.minAmountMinor || amountMinor > offer.ceilingMinor) {
            throw BusinessException(
                "INVALID_CREDIT_AMOUNT",
                "Choisissez un montant entre ${NotificationService.money(offer.minAmountMinor)} et ${NotificationService.money(offer.ceilingMinor)}",
            )
        }
        return offer
    }

    fun quote(customerId: UUID, amountMinor: Long, durationMonths: Int): CreditQuote {
        val s = settings()
        val offer = requireOffer(customer(customerId), s, amountMinor, durationMonths)
        return quoteOf(amountMinor, durationMonths, offer.monthlyRateBp, amountMinor > offer.reviewThresholdMinor)
    }

    private fun quoteOf(amountMinor: Long, durationMonths: Int, rateBp: Int, needsReview: Boolean): CreditQuote {
        val schedule = CreditMath.schedule(amountMinor, durationMonths, rateBp, today())
        val interest = schedule.sumOf { it.interestMinor }
        return CreditQuote(
            amountMinor, durationMonths, rateBp, CreditMath.aprBp(rateBp), schedule.first().amountMinor, interest,
            amountMinor + interest, needsReview, schedule,
        )
    }

    // ---------- Demande et versement ----------

    /** Le client accepte l'offre (PIN déjà vérifié). Sous le seuil de revue, l'argent est versé tout de suite. */
    fun apply(customerId: UUID, idempotencyKey: String, amountMinor: Long, durationMonths: Int): CreditView {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 100) throw BusinessException("INVALID_IDEMPOTENCY_KEY", "Clé d'idempotence vide ou trop longue")
        return tx.execute {
            jdbc.sql("SELECT id FROM customer WHERE id = :id FOR UPDATE").param("id", customerId).query(UUID::class.java).single()
            jdbc.sql("SELECT * FROM credit WHERE customer_id = :c AND idempotency_key = :k").param("c", customerId).param("k", idempotencyKey)
                .query(::map).optional().orElse(null)?.let { return@execute view(it) }
            val c = customer(customerId)
            val s = settings()
            val offer = requireOffer(c, s, amountMinor, durationMonths)
            val review = amountMinor > offer.reviewThresholdMinor
            val id = UUID.randomUUID()
            jdbc.sql(
                """INSERT INTO credit (id, customer_id, status, principal_minor, duration_months, monthly_rate_bp, score, ceiling_minor, factors, idempotency_key, created_at)
                   VALUES (:id, :c, :status, :p, :n, :rate, :score, :ceiling, CAST(:factors AS jsonb), :key, :now)""",
            ).param("id", id).param("c", customerId).param("status", if (review) "PENDING_REVIEW" else "ACTIVE").param("p", amountMinor)
                .param("n", durationMonths).param("rate", offer.monthlyRateBp).param("score", offer.score).param("ceiling", offer.ceilingMinor)
                .param("factors", json.writeValueAsString(offer.factors)).param("key", idempotencyKey).param("now", now()).update()
            audit.record(ActorType.CUSTOMER, customerId, "CREDIT_REQUESTED", "CREDIT", id, "$amountMinor · $durationMonths mois · score ${offer.score}")
            if (!review) disburse(row(id, lock = true)!!)
            view(row(id)!!)
        }!!
    }

    /** Versement : créance au débit, portefeuille au crédit, échéancier à partir d'aujourd'hui. */
    private fun disburse(r: Row) {
        val c = customer(r.customerId)
        if (c.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Le compte du client est suspendu ou fermé")
        val walletId = wallet(r.customerId).id
        ledgerRepository.lockAccounts(listOf(walletId))
        try {
            limits.checkIncoming(c, walletId, r.principalMinor)
        } catch (e: LimitExceededException) {
            throw BusinessException("LIMIT_MAX_BALANCE", "Ce crédit dépasserait le solde maximal de votre niveau. Choisissez un montant plus petit.")
        }
        val posted = ledger.post(
            PostingRequest(
                idempotencyKey = "credit:disburse:${r.id}", type = "CREDIT_DISBURSEMENT", description = null,
                lines = listOf(PostingLine(RECEIVABLE, Direction.DEBIT, r.principalMinor), PostingLine(walletId, Direction.CREDIT, r.principalMinor)),
            ),
        )
        insertSchedule(r.id, CreditMath.schedule(r.principalMinor, r.durationMonths, r.monthlyRateBp, today()))
        jdbc.sql("UPDATE credit SET status = 'ACTIVE', disbursed_at = :now, disbursement_transaction_id = :tx WHERE id = :id")
            .param("now", now()).param("tx", posted.transaction.id).param("id", r.id).update()
        audit.record(ActorType.SYSTEM, null, "CREDIT_DISBURSED", "CREDIT", r.id, "${r.principalMinor}")
    }

    private fun insertSchedule(creditId: UUID, plan: List<PlannedInstalment>) = plan.forEach {
        jdbc.sql(
            """INSERT INTO credit_instalment (id, credit_id, number, due_date, principal_minor, interest_minor, status)
               VALUES (:id, :c, :n, :d, :p, :i, 'UPCOMING')""",
        ).param("id", UUID.randomUUID()).param("c", creditId).param("n", it.number).param("d", it.dueDate)
            .param("p", it.principalMinor).param("i", it.interestMinor).update()
    }

    /** Le client retire sa demande tant qu'elle est en examen. */
    fun cancel(customerId: UUID, creditId: UUID): CreditView = tx.execute {
        val r = owned(customerId, creditId, lock = true)
        if (r.status != CreditStatus.PENDING_REVIEW) throw BusinessException("CREDIT_NOT_PENDING", "Seule une demande en examen peut être retirée")
        jdbc.sql("UPDATE credit SET status = 'REJECTED', decision_note = :note, decided_at = :now, closed_at = :now WHERE id = :id")
            .param("note", "Demande retirée par le client").param("now", now()).param("id", creditId).update()
        audit.record(ActorType.CUSTOMER, customerId, "CREDIT_CANCELLED", "CREDIT", creditId)
        view(row(creditId)!!)
    }!!

    fun get(customerId: UUID, creditId: UUID): CreditView = view(owned(customerId, creditId))

    // ---------- Remboursements ----------

    /**
     * Le client rembourse depuis l'app (PIN déjà vérifié) :
     * DUE paie ce qui est dû (en partie si le solde ne suffit pas), ou l'échéance suivante en avance s'il n'y a rien de dû ;
     * FULL solde le crédit : ce qui est dû, plus le capital restant, sans les intérêts des mois à venir.
     */
    fun repay(customerId: UUID, creditId: UUID, idempotencyKey: String, mode: RepaymentMode): CreditView {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 100) throw BusinessException("INVALID_IDEMPOTENCY_KEY", "Clé d'idempotence vide ou trop longue")
        return tx.execute {
            val r = owned(customerId, creditId, lock = true)
            val key = "credit:repay:${r.id}:$idempotencyKey"
            if (ledgerRepository.findTransactionHeaderByKey(key) != null) return@execute view(r)
            if (r.status !in RUNNING) throw BusinessException("CREDIT_NOT_RUNNING", "Ce crédit n'a plus rien à rembourser")
            val walletId = wallet(customerId).id
            ledgerRepository.lockAccounts(listOf(walletId))
            val available = ledgerRepository.balance(walletId)
            val today = today()
            val open = instalments(r.id).filter { it.open }
            val due = open.filter { !it.dueDate.isAfter(today) }
            val allocations: List<Pair<InstalmentRow, Long>> = when (mode) {
                RepaymentMode.FULL -> {
                    val payoff = due.sumOf { it.remainingMinor } + open.filter { it.dueDate.isAfter(today) }.sumOf { it.principalRemainingMinor }
                    if (available < payoff) throw BusinessException("INSUFFICIENT_FUNDS", "Il faut ${NotificationService.money(payoff)} sur votre solde pour tout rembourser")
                    open.map { it to if (it.dueDate.isAfter(today)) it.principalRemainingMinor else it.remainingMinor }
                }
                RepaymentMode.DUE -> if (due.isNotEmpty()) {
                    if (available <= 0) throw BusinessException("INSUFFICIENT_FUNDS", "Votre solde est vide : rechargez votre compte pour rembourser")
                    allocate(due, available)
                } else {
                    val next = open.first()
                    if (available < next.remainingMinor) {
                        throw BusinessException("INSUFFICIENT_FUNDS", "Il faut ${NotificationService.money(next.remainingMinor)} sur votre solde pour payer l'échéance en avance")
                    }
                    listOf(next to next.remainingMinor)
                }
            }
            post(r, walletId, key, allocations, waiveFutureInterest = mode == RepaymentMode.FULL)
            refresh(r.id)
            view(row(r.id)!!)
        }!!
    }

    /** Répartit [available] sur les échéances, la plus vieille d'abord. */
    private fun allocate(rows: List<InstalmentRow>, available: Long): List<Pair<InstalmentRow, Long>> {
        var left = available
        return rows.mapNotNull { i ->
            val pay = minOf(left, i.remainingMinor)
            left -= pay
            if (pay > 0) i to pay else null
        }
    }

    /**
     * Une écriture par prélèvement : portefeuille au débit ; capital vers la créance, intérêts vers les revenus.
     * Sur chaque échéance, les intérêts sont payés avant le capital.
     */
    private fun post(r: Row, walletId: UUID, key: String, allocations: List<Pair<InstalmentRow, Long>>, waiveFutureInterest: Boolean) {
        if (allocations.isEmpty()) return
        var principal = 0L
        var interest = 0L
        val now = now()
        val today = today()
        for ((i, pay) in allocations) {
            val interestLeft = (i.interestMinor - i.paidMinor).coerceAtLeast(0)
            val waive = waiveFutureInterest && i.dueDate.isAfter(today)
            val toInterest = if (waive) 0 else minOf(pay, interestLeft)
            interest += toInterest
            principal += pay - toInterest
            val paid = i.paidMinor + pay
            // Remboursement anticipé : les intérêts pas encore payés d'une échéance à venir sont annulés.
            val newInterest = if (waive) i.interestMinor - interestLeft else i.interestMinor
            val done = paid >= i.principalMinor + newInterest
            jdbc.sql(
                """UPDATE credit_instalment SET paid_minor = :paid, interest_minor = :interest, status = :status, paid_at = :at WHERE id = :id""",
            ).param("paid", paid).param("interest", newInterest)
                .param("status", if (done) "PAID" else i.status.name).param("at", if (done) now else null).param("id", i.id).update()
        }
        val lines = buildList {
            add(PostingLine(walletId, Direction.DEBIT, principal + interest))
            if (principal > 0) add(PostingLine(RECEIVABLE, Direction.CREDIT, principal))
            if (interest > 0) add(PostingLine(INTEREST, Direction.CREDIT, interest))
        }
        ledger.post(PostingRequest(idempotencyKey = key, type = "CREDIT_REPAYMENT", description = null, lines = lines))
    }

    /** Recalcule l'état du crédit après un prélèvement, un jour qui passe ou un rééchelonnement (diagramme 4.3). */
    private fun refresh(creditId: UUID) {
        val r = row(creditId, lock = true) ?: return
        if (r.status !in RUNNING) return
        val today = today()
        val all = instalments(creditId)
        val open = all.filter { it.open }
        if (open.isEmpty()) {
            jdbc.sql("UPDATE credit SET status = 'REPAID', closed_at = :now WHERE id = :id").param("now", now()).param("id", creditId).update()
            audit.record(ActorType.SYSTEM, null, "CREDIT_REPAID", "CREDIT", creditId)
            notifications.creditRepaid(r.customerId, creditId, r.principalMinor)
            return
        }
        val late = open.filter { !it.dueDate.isAfter(today) }
        late.filter { it.status != InstalmentStatus.LATE }.forEach {
            jdbc.sql("UPDATE credit_instalment SET status = 'LATE' WHERE id = :id").param("id", it.id).update()
        }
        late.filter { !it.lateNotified }.forEach {
            jdbc.sql("UPDATE credit_instalment SET late_notified = TRUE WHERE id = :id").param("id", it.id).update()
            notifications.creditLate(r.customerId, creditId, it.number, it.dueDate, it.remainingMinor)
        }
        val defaultAfter = settings().defaultAfterDays
        val status = when {
            r.status == CreditStatus.DEFAULTED -> CreditStatus.DEFAULTED // seul le back-office le sort du défaut
            late.isEmpty() -> CreditStatus.ACTIVE
            ChronoUnit.DAYS.between(late.first().dueDate, today) >= defaultAfter -> CreditStatus.DEFAULTED
            else -> CreditStatus.LATE
        }
        if (status != r.status) {
            jdbc.sql("UPDATE credit SET status = :s WHERE id = :id").param("s", status.name).param("id", creditId).update()
            audit.record(ActorType.SYSTEM, null, "CREDIT_$status", "CREDIT", creditId)
            if (status == CreditStatus.DEFAULTED) notifications.creditDefaulted(r.customerId, creditId, late.sumOf { it.remainingMinor })
        }
    }

    /**
     * Tâche planifiée : rappel deux jours avant chaque échéance, prélèvement des échéances arrivées
     * (en partie si le solde ne suffit pas), passage en retard puis en défaut. Chaque crédit dans sa propre transaction.
     */
    fun runDue(): Int {
        val today = today()
        jdbc.sql(
            """SELECT i.id, c.customer_id, c.id AS credit_id, i.due_date, i.principal_minor + i.interest_minor - i.paid_minor AS remaining
               FROM credit_instalment i JOIN credit c ON c.id = i.credit_id
               WHERE i.status = 'UPCOMING' AND NOT i.reminder_sent AND i.due_date > :today AND i.due_date <= :soon AND c.status IN ('ACTIVE', 'LATE')""",
        ).param("today", today).param("soon", today.plusDays(REMINDER_DAYS)).query { rs, _ ->
            listOf(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, LocalDate::class.java), rs.getLong(5))
        }.list().forEach { (id, customerId, creditId, dueDate, remaining) ->
            tx.executeWithoutResult {
                if (jdbc.sql("UPDATE credit_instalment SET reminder_sent = TRUE WHERE id = :id AND NOT reminder_sent").param("id", id).update() == 1) {
                    notifications.creditReminder(customerId as UUID, creditId as UUID, dueDate as LocalDate, remaining as Long)
                }
            }
        }
        val credits = jdbc.sql(
            """SELECT DISTINCT c.id FROM credit c JOIN credit_instalment i ON i.credit_id = c.id
               WHERE c.status IN ('ACTIVE', 'LATE', 'DEFAULTED') AND i.status IN ('UPCOMING', 'LATE') AND i.due_date <= :today""",
        ).param("today", today).query(UUID::class.java).list()
        var collected = 0
        for (id in credits) {
            try {
                tx.executeWithoutResult {
                    val r = row(id, lock = true) ?: return@executeWithoutResult
                    if (r.status !in RUNNING) return@executeWithoutResult
                    val walletId = wallet(r.customerId).id
                    ledgerRepository.lockAccounts(listOf(walletId))
                    val due = instalments(id).filter { it.open && !it.dueDate.isAfter(today) }
                    val available = ledgerRepository.balance(walletId)
                    if (available > 0 && due.isNotEmpty()) {
                        val paidSoFar = due.sumOf { it.paidMinor }
                        post(r, walletId, "credit:collect:$id:$today:${due.first().number}:$paidSoFar", allocate(due, available), waiveFutureInterest = false)
                        collected++
                    }
                    refresh(id)
                }
            } catch (e: Exception) {
                log.warn("Prélèvement du crédit {} impossible : {}", id, e.message)
            }
        }
        return collected
    }

    // ---------- Back-office ----------

    private fun backoffice(r: Row): BackofficeCredit {
        val c = customer(r.customerId)
        val decidedBy = r.decidedBy?.let {
            jdbc.sql("SELECT full_name FROM backoffice_agent WHERE id = :id").param("id", it).query(String::class.java).optional().orElse(null)
        }
        return BackofficeCredit(
            view(r), c.id, c.fullName, c.phone, c.kycLevel, ledgerRepository.balance(wallet(c.id).id), r.ceilingMinor,
            json.readValue(r.factors, object : TypeReference<List<CreditFactor>>() {}), decidedBy, r.decidedAt,
        )
    }

    fun list(status: CreditStatus?): List<BackofficeCredit> {
        val spec = if (status == null) {
            jdbc.sql("SELECT * FROM credit WHERE status IN (:open) ORDER BY created_at LIMIT 200").param("open", OPEN.map { it.name })
        } else {
            jdbc.sql("SELECT * FROM credit WHERE status = :s ORDER BY created_at ${if (status in OPEN) "" else "DESC"} LIMIT 200").param("s", status.name)
        }
        return spec.query(::map).list().map(::backoffice)
    }

    fun forCustomer(customerId: UUID): List<BackofficeCredit> =
        jdbc.sql("SELECT * FROM credit WHERE customer_id = :c ORDER BY created_at DESC").param("c", customerId).query(::map).list().map(::backoffice)

    fun backofficeGet(id: UUID): BackofficeCredit = backoffice(row(id) ?: throw NotFoundException("CREDIT_NOT_FOUND", "Crédit introuvable"))

    private fun requireNote(note: String?, what: String): String =
        note?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 500) throw BusinessException("INVALID_NOTE", "500 caractères au plus") }
            ?: throw BusinessException("NOTE_REQUIRED", "Expliquez $what en quelques mots")

    /** Un agent de crédit approuve une demande au-dessus du seuil : l'argent est versé tout de suite. */
    fun approve(agentId: UUID, id: UUID, note: String?): BackofficeCredit {
        tx.executeWithoutResult {
            val r = row(id, lock = true) ?: throw NotFoundException("CREDIT_NOT_FOUND", "Crédit introuvable")
            if (r.status != CreditStatus.PENDING_REVIEW) throw ConflictException("CREDIT_ALREADY_DECIDED", "Cette demande a déjà été traitée")
            jdbc.sql("UPDATE credit SET decided_by = :a, decided_at = :now, decision_note = :note WHERE id = :id")
                .param("a", agentId).param("now", now()).param("note", note?.trim()?.takeIf { it.isNotEmpty() }?.take(500)).param("id", id).update()
            audit.record(ActorType.AGENT, agentId, "CREDIT_APPROVED", "CREDIT", id, note?.take(200))
            disburse(r)
        }
        return backofficeGet(id)
    }

    fun reject(agentId: UUID, id: UUID, note: String?): BackofficeCredit {
        val reason = requireNote(note, "le refus au client")
        tx.executeWithoutResult {
            val r = row(id, lock = true) ?: throw NotFoundException("CREDIT_NOT_FOUND", "Crédit introuvable")
            if (r.status != CreditStatus.PENDING_REVIEW) throw ConflictException("CREDIT_ALREADY_DECIDED", "Cette demande a déjà été traitée")
            jdbc.sql("UPDATE credit SET status = 'REJECTED', decided_by = :a, decided_at = :now, closed_at = :now, decision_note = :note WHERE id = :id")
                .param("a", agentId).param("now", now()).param("note", reason).param("id", id).update()
            audit.record(ActorType.AGENT, agentId, "CREDIT_REJECTED", "CREDIT", id, reason.take(200))
            notifications.creditRejected(r.customerId, id, r.principalMinor, reason)
        }
        return backofficeGet(id)
    }

    /**
     * Rééchelonnement (accord du back-office) : le capital restant et les intérêts en retard sont étalés
     * sur une nouvelle durée, à partir d'aujourd'hui. Les anciennes échéances impayées sont annulées.
     */
    fun reschedule(agentId: UUID, id: UUID, durationMonths: Int, note: String?): BackofficeCredit {
        val reason = requireNote(note, "l'accord trouvé avec le client")
        if (durationMonths !in 1..24) throw BusinessException("INVALID_DURATION", "Choisissez une durée de 1 à 24 mois")
        tx.executeWithoutResult {
            val r = row(id, lock = true) ?: throw NotFoundException("CREDIT_NOT_FOUND", "Crédit introuvable")
            if (r.status != CreditStatus.LATE && r.status != CreditStatus.DEFAULTED) {
                throw BusinessException("CREDIT_NOT_LATE", "Seul un crédit en retard ou en défaut se rééchelonne")
            }
            val today = today()
            val all = instalments(id)
            val open = all.filter { it.open }
            val principal = open.sumOf { it.principalRemainingMinor }
            val carriedInterest = open.filter { !it.dueDate.isAfter(today) }.sumOf { (it.interestMinor - it.paidMinor).coerceAtLeast(0) }
            open.forEach {
                // L'échéance garde ce qui a déjà été payé ; le reste passe dans le nouvel échéancier.
                val keptInterest = minOf(it.paidMinor, it.interestMinor)
                jdbc.sql("UPDATE credit_instalment SET status = 'CANCELLED', interest_minor = :i, principal_minor = :p WHERE id = :id")
                    .param("i", keptInterest).param("p", it.paidMinor - keptInterest).param("id", it.id).update()
            }
            val plan = CreditMath.schedule(principal, durationMonths, r.monthlyRateBp, today, firstNumber = all.maxOf { it.number } + 1, carriedInterestMinor = carriedInterest)
            insertSchedule(id, plan)
            jdbc.sql("UPDATE credit SET status = 'ACTIVE', reschedule_count = reschedule_count + 1, decision_note = :note WHERE id = :id")
                .param("note", reason).param("id", id).update()
            audit.record(ActorType.AGENT, agentId, "CREDIT_RESCHEDULED", "CREDIT", id, "$durationMonths mois · $reason".take(200))
            notifications.creditRescheduled(r.customerId, id, plan.size, plan.first().dueDate, plan.first().amountMinor)
        }
        return backofficeGet(id)
    }

    /** Décision du comité de crédit : le capital restant d'un crédit en défaut passe en perte. */
    fun writeOff(agentId: UUID, id: UUID, note: String?): BackofficeCredit {
        val reason = requireNote(note, "la décision du comité")
        tx.executeWithoutResult {
            val r = row(id, lock = true) ?: throw NotFoundException("CREDIT_NOT_FOUND", "Crédit introuvable")
            if (r.status != CreditStatus.DEFAULTED) throw BusinessException("CREDIT_NOT_DEFAULTED", "Seul un crédit en défaut passe en perte")
            val open = instalments(id).filter { it.open }
            val principal = open.sumOf { it.principalRemainingMinor }
            open.forEach { jdbc.sql("UPDATE credit_instalment SET status = 'CANCELLED' WHERE id = :id").param("id", it.id).update() }
            if (principal > 0) {
                ledger.post(
                    PostingRequest(
                        idempotencyKey = "credit:write-off:$id", type = "CREDIT_WRITE_OFF", description = reason.take(200),
                        lines = listOf(PostingLine(LOSSES, Direction.DEBIT, principal), PostingLine(RECEIVABLE, Direction.CREDIT, principal)),
                    ),
                )
            }
            jdbc.sql("UPDATE credit SET status = 'WRITTEN_OFF', closed_at = :now, decided_by = :a, decided_at = :now, decision_note = :note WHERE id = :id")
                .param("now", now()).param("a", agentId).param("note", reason).param("id", id).update()
            audit.record(ActorType.AGENT, agentId, "CREDIT_WRITTEN_OFF", "CREDIT", id, "$principal · ${reason.take(150)}")
        }
        return backofficeGet(id)
    }

    /** Pour la fermeture du compte : un crédit en cours ou en examen l'empêche. */
    fun hasOpenCredit(customerId: UUID): Boolean = openCredit(customerId) != null
}
