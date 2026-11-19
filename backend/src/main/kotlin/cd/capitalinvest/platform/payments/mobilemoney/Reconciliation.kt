package cd.capitalinvest.platform.payments.mobilemoney

import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.payments.Operator
import cd.capitalinvest.platform.payments.Topup
import cd.capitalinvest.platform.payments.TopupService
import cd.capitalinvest.platform.payments.TopupStatus
import cd.capitalinvest.platform.payments.Withdrawal
import cd.capitalinvest.platform.payments.WithdrawalService
import cd.capitalinvest.platform.payments.label
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

enum class DiscrepancyKind {
    CONFIRMED_NOT_CREDITED, CREDITED_NOT_CONFIRMED, // recharges
    PAID_BUT_REFUNDED, MARKED_PAID_NOT_PAID, // retraits
    AMOUNT_MISMATCH, STILL_PENDING,
}

/** Sens de l'argent : recharge (entrée) ou retrait (sortie). */
enum class MovementType { TOPUP, WITHDRAWAL }

data class ReconciliationRun(
    val id: UUID,
    val businessDate: LocalDate,
    val startedAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val checked: Int,
    val matched: Int,
    val discrepancies: Int,
)

data class ReconciliationItem(
    val id: UUID,
    val businessDate: LocalDate,
    val type: MovementType,
    /** Identifiant de la recharge ou du retrait. */
    val targetId: UUID,
    val kind: DiscrepancyKind,
    val ourStatus: String,
    val providerStatus: String,
    val ourAmountMinor: Long,
    val providerAmountMinor: Long?,
    val operator: Operator,
    val provider: String,
    val customerName: String,
    val customerPhone: String,
    val topupCreatedAt: OffsetDateTime,
    val resolvedAt: OffsetDateTime?,
    val resolvedByName: String?,
    val resolution: String?,
)

/** Recharge ou retrait à vérifier, avec ce qu'il faut pour décider. */
data class ReviewTopup(
    val id: UUID,
    val type: MovementType,
    val operator: Operator,
    val provider: String,
    val amountMinor: Long,
    val currency: String,
    val reason: String?,
    val customerName: String,
    val customerPhone: String,
    val checks: Int,
    val createdAt: OffsetDateTime,
)

/**
 * Rapprochement : chaque recharge d'une journée est comparée à son statut chez le prestataire.
 * Tout écart devient une ligne à traiter par la conformité ; rien n'est corrigé sans un agent.
 */
@Service
class ReconciliationService(
    private val jdbc: JdbcClient,
    private val topups: TopupService,
    private val withdrawals: WithdrawalService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    limits: LimitsProperties,
) {
    private val zone = ZoneId.of(limits.timezone)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Rapproche une journée (heure de Lubumbashi). Relancer la même journée remplace les écarts encore ouverts. */
    fun run(date: LocalDate): ReconciliationRun {
        val runId = tx.execute {
            val existing = jdbc.sql("SELECT id FROM reconciliation_run WHERE business_date = :d FOR UPDATE")
                .param("d", date).query(UUID::class.java).optional().orElse(null)
            if (existing != null) {
                jdbc.sql("DELETE FROM reconciliation_item WHERE run_id = :r AND resolved_at IS NULL").param("r", existing).update()
                jdbc.sql("UPDATE reconciliation_run SET started_at = :now, finished_at = NULL WHERE id = :r")
                    .param("now", now()).param("r", existing).update()
                existing
            } else {
                UUID.randomUUID().also {
                    jdbc.sql("INSERT INTO reconciliation_run (id, business_date, started_at) VALUES (:id, :d, :now)")
                        .param("id", it).param("d", date).param("now", now()).update()
                }
            }
        }!!

        val from = date.atStartOfDay(zone).toOffsetDateTime()
        val to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        // Ce qui est déjà à vérifier est dans sa propre file ; ce qui a déjà été tranché dans ce rapprochement aussi.
        fun ids(table: String, column: String) = jdbc.sql(
            """SELECT t.id FROM $table t
               WHERE t.created_at >= :from AND t.created_at < :to AND t.status <> 'REVIEW'
                 AND NOT EXISTS (SELECT 1 FROM reconciliation_item i WHERE i.run_id = :run AND i.$column = t.id)
               ORDER BY t.created_at""",
        ).param("from", from).param("to", to).param("run", runId).query(UUID::class.java).list()

        var checked = 0
        var matched = 0
        var discrepancies = 0
        fun record(type: MovementType, id: UUID, status: TopupStatus, amount: Long, found: Triple<DiscrepancyKind?, String, Long?>) {
            checked++
            val (kind, providerStatus, providerAmount) = found
            if (kind == null) {
                matched++
                return
            }
            discrepancies++
            jdbc.sql(
                """INSERT INTO reconciliation_item (id, run_id, topup_id, withdrawal_id, kind, our_status, provider_status,
                                                    our_amount_minor, provider_amount_minor, created_at)
                   VALUES (:id, :run, :topup, :withdrawal, :kind, :ours, :theirs, :amount, :pAmount, :now)""",
            ).param("id", UUID.randomUUID()).param("run", runId)
                .param("topup", if (type == MovementType.TOPUP) id else null)
                .param("withdrawal", if (type == MovementType.WITHDRAWAL) id else null)
                .param("kind", kind.name).param("ours", status.name).param("theirs", providerStatus).param("amount", amount)
                .param("pAmount", providerAmount).param("now", now()).update()
        }

        for (id in ids("topup", "topup_id")) {
            // Une recharge encore en attente est d'abord relue : souvent, elle se termine ici.
            val topup = topups.get(id).let { if (it.status == TopupStatus.PENDING) topups.refresh(id) else it }
            if (topup.status == TopupStatus.REVIEW) continue
            record(MovementType.TOPUP, id, topup.status, topup.amountMinor, compare(topup))
        }
        for (id in ids("withdrawal", "withdrawal_id")) {
            val w = withdrawals.get(id).let { if (it.status == TopupStatus.PENDING) withdrawals.refresh(id) else it }
            if (w.status == TopupStatus.REVIEW) continue
            record(MovementType.WITHDRAWAL, id, w.status, w.amountMinor, compare(w))
        }
        jdbc.sql("UPDATE reconciliation_run SET finished_at = :now, checked = :c, matched = :m, discrepancies = :d WHERE id = :r")
            .param("now", now()).param("c", checked).param("m", matched).param("d", discrepancies).param("r", runId).update()
        return runs(1, runId).first()
    }

    private fun compare(topup: Topup): Triple<DiscrepancyKind?, String, Long?> {
        if (topup.status == TopupStatus.PENDING) return Triple(DiscrepancyKind.STILL_PENDING, "PENDING", null)
        val result = topups.check(topup)
        val amount = (result as? CheckResult.Completed)?.amountMinor
        val kind = when (topup.status) {
            TopupStatus.CONFIRMED -> when (result) {
                is CheckResult.Completed ->
                    if (result.amountMinor == topup.amountMinor && result.currency == topup.currency.name) null
                    else DiscrepancyKind.AMOUNT_MISMATCH
                is CheckResult.Failed, CheckResult.NotFound -> DiscrepancyKind.CREDITED_NOT_CONFIRMED
                else -> DiscrepancyKind.STILL_PENDING
            }
            else -> when (result) {
                is CheckResult.Completed -> DiscrepancyKind.CONFIRMED_NOT_CREDITED
                is CheckResult.Failed, CheckResult.NotFound -> null
                else -> DiscrepancyKind.STILL_PENDING
            }
        }
        return Triple(kind, label(result), amount)
    }

    private fun compare(w: Withdrawal): Triple<DiscrepancyKind?, String, Long?> {
        if (w.status == TopupStatus.PENDING) return Triple(DiscrepancyKind.STILL_PENDING, "PENDING", null)
        val result = withdrawals.check(w)
        val amount = (result as? CheckResult.Completed)?.amountMinor
        val kind = when (w.status) {
            TopupStatus.CONFIRMED -> when (result) {
                // Un versement partiel accepté par un agent garde le montant demandé : seul un versement supérieur est un écart.
                is CheckResult.Completed ->
                    if (result.amountMinor <= w.amountMinor && result.currency == w.currency.name) null
                    else DiscrepancyKind.AMOUNT_MISMATCH
                is CheckResult.Failed, CheckResult.NotFound -> DiscrepancyKind.MARKED_PAID_NOT_PAID
                else -> DiscrepancyKind.STILL_PENDING
            }
            else -> when (result) {
                is CheckResult.Completed -> DiscrepancyKind.PAID_BUT_REFUNDED
                is CheckResult.Failed, CheckResult.NotFound -> null
                else -> DiscrepancyKind.STILL_PENDING
            }
        }
        return Triple(kind, label(result), amount)
    }

    fun runs(limit: Int, id: UUID? = null): List<ReconciliationRun> =
        jdbc.sql(
            "SELECT * FROM reconciliation_run ${if (id != null) "WHERE id = :id" else ""} ORDER BY business_date DESC LIMIT :limit",
        ).apply { if (id != null) param("id", id) }.param("limit", limit).query { rs, _ ->
            ReconciliationRun(
                rs.getObject("id", UUID::class.java), rs.getObject("business_date", LocalDate::class.java),
                rs.getObject("started_at", OffsetDateTime::class.java), rs.getObject("finished_at", OffsetDateTime::class.java),
                rs.getInt("checked"), rs.getInt("matched"), rs.getInt("discrepancies"),
            )
        }.list()

    fun items(open: Boolean, limit: Int): List<ReconciliationItem> =
        jdbc.sql(
            """SELECT i.*, r.business_date,
                      COALESCE(t.operator, w.operator) AS operator, COALESCE(t.provider, w.provider) AS provider,
                      COALESCE(t.created_at, w.created_at) AS topup_created_at,
                      COALESCE(c.full_name, 'Marchand ' || m.name) AS full_name, COALESCE(c.phone, m.phone) AS phone,
                      a.full_name AS agent_name
               FROM reconciliation_item i
               JOIN reconciliation_run r ON r.id = i.run_id
               LEFT JOIN topup t ON t.id = i.topup_id
               LEFT JOIN withdrawal w ON w.id = i.withdrawal_id
               LEFT JOIN customer c ON c.id = COALESCE(t.customer_id, w.customer_id)
               -- Un reversement de marchand passe par les mêmes contrôles qu'un retrait client.
               LEFT JOIN merchant m ON m.id = w.merchant_id
               LEFT JOIN backoffice_agent a ON a.id = i.resolved_by
               ${if (open) "WHERE i.resolved_at IS NULL" else ""}
               ORDER BY i.resolved_at DESC NULLS FIRST, r.business_date DESC, i.created_at
               LIMIT :limit""",
        ).param("limit", limit).query(::item).list()

    private fun item(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = ReconciliationItem(
        id = rs.getObject("id", UUID::class.java),
        businessDate = rs.getObject("business_date", LocalDate::class.java),
        type = if (rs.getObject("topup_id") != null) MovementType.TOPUP else MovementType.WITHDRAWAL,
        targetId = (rs.getObject("topup_id", UUID::class.java) ?: rs.getObject("withdrawal_id", UUID::class.java)),
        kind = DiscrepancyKind.valueOf(rs.getString("kind")),
        ourStatus = rs.getString("our_status"),
        providerStatus = rs.getString("provider_status"),
        ourAmountMinor = rs.getLong("our_amount_minor"),
        providerAmountMinor = rs.getObject("provider_amount_minor") as Long?,
        operator = Operator.valueOf(rs.getString("operator")),
        provider = rs.getString("provider"),
        customerName = rs.getString("full_name"),
        customerPhone = rs.getString("phone"),
        topupCreatedAt = rs.getObject("topup_created_at", OffsetDateTime::class.java),
        resolvedAt = rs.getObject("resolved_at", OffsetDateTime::class.java),
        resolvedByName = rs.getString("agent_name"),
        resolution = rs.getString("resolution"),
    )

    fun reviewQueue(): List<ReviewTopup> =
        jdbc.sql(
            """SELECT 'TOPUP' AS type, t.id, t.operator, t.provider, t.amount_minor, t.currency, t.review_reason, t.checks,
                      t.created_at, c.full_name, c.phone
               FROM topup t JOIN customer c ON c.id = t.customer_id WHERE t.status = 'REVIEW'
               UNION ALL
               SELECT 'WITHDRAWAL', w.id, w.operator, w.provider, w.amount_minor, w.currency, w.review_reason, w.checks,
                      w.created_at, COALESCE(c.full_name, 'Marchand ' || m.name), COALESCE(c.phone, m.phone)
               FROM withdrawal w LEFT JOIN customer c ON c.id = w.customer_id LEFT JOIN merchant m ON m.id = w.merchant_id
               WHERE w.status = 'REVIEW'
               ORDER BY created_at""",
        ).query { rs, _ ->
            ReviewTopup(
                rs.getObject("id", UUID::class.java), MovementType.valueOf(rs.getString("type")),
                Operator.valueOf(rs.getString("operator")), rs.getString("provider"),
                rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("review_reason"),
                rs.getString("full_name"), rs.getString("phone"), rs.getInt("checks"),
                rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list()

    /**
     * Traite un écart. CREDIT n'est possible que si l'opérateur a encaissé sans que le client soit crédité,
     * et seulement s'il le confirme encore à l'instant ; CLOSE consigne une correction faite hors de l'outil.
     */
    fun resolveItem(agent: UUID, itemId: UUID, credit: Boolean, note: String): ReconciliationItem {
        data class Row(val topupId: UUID?, val withdrawalId: UUID?, val kind: DiscrepancyKind)
        val row = jdbc.sql("SELECT topup_id, withdrawal_id, kind, resolved_at FROM reconciliation_item WHERE id = :id")
            .param("id", itemId).query { rs, _ ->
                if (rs.getObject("resolved_at") != null) throw ConflictException("ITEM_ALREADY_RESOLVED", "Écart déjà traité")
                Row(rs.getObject("topup_id", UUID::class.java), rs.getObject("withdrawal_id", UUID::class.java), DiscrepancyKind.valueOf(rs.getString("kind")))
            }.optional().orElseThrow { NotFoundException("ITEM_NOT_FOUND", "Écart introuvable") }
        if (credit) {
            // Créditer le client : recharge encaissée sans crédit, ou retrait noté versé que l'opérateur n'a pas payé.
            when (row.kind) {
                DiscrepancyKind.CONFIRMED_NOT_CREDITED ->
                    topups.resolve(row.topupId!!, credit = true, note = note, allowed = setOf(TopupStatus.FAILED))
                DiscrepancyKind.MARKED_PAID_NOT_PAID ->
                    withdrawals.resolve(row.withdrawalId!!, WithdrawalService.Decision.REFUND, note)
                else -> throw BusinessException("CREDIT_NOT_ALLOWED", "Créditer n'est possible que si le client a perdu de l'argent")
            }
        }
        val updated = jdbc.sql(
            """UPDATE reconciliation_item SET resolved_at = :now, resolved_by = :agent, resolution = :res
               WHERE id = :id AND resolved_at IS NULL""",
        ).param("now", now()).param("agent", agent).param("res", "${if (credit) "Crédité" else "Clos"} : $note".take(500))
            .param("id", itemId).update()
        if (updated == 0) throw ConflictException("ITEM_ALREADY_RESOLVED", "Écart déjà traité")
        return items(open = false, limit = 1000).first { it.id == itemId }
    }
}

data class ResolveRequest(
    val action: ResolveAction,
    @field:NotBlank @field:Size(max = 300) val note: String,
)

enum class ResolveAction { CREDIT, CLOSE }

data class WithdrawalResolveRequest(
    val action: WithdrawalService.Decision,
    @field:NotBlank @field:Size(max = 300) val note: String,
)

data class RunRequest(val businessDate: LocalDate? = null)

/** Écrans « Rapprochement » et « Recharges à vérifier » du back-office. Conformité et administrateurs. */
@RestController
@RequestMapping("/v1/backoffice")
class ReconciliationController(
    private val reconciliation: ReconciliationService,
    private val topups: TopupService,
    private val withdrawals: WithdrawalService,
    private val audit: AuditLog,
    private val clock: Clock,
    private val limits: LimitsProperties,
) {
    private fun requireAccess(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    @GetMapping("/reconciliation/runs")
    fun runs(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestParam(defaultValue = "30") limit: Int): List<ReconciliationRun> {
        requireAccess(me)
        return reconciliation.runs(limit.coerceIn(1, 365))
    }

    /** Lance le rapprochement d'une journée (par défaut la veille). */
    @PostMapping("/reconciliation/runs")
    fun run(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestBody(required = false) body: RunRequest?): ReconciliationRun {
        requireAccess(me)
        val date = body?.businessDate ?: LocalDate.now(clock.withZone(ZoneId.of(limits.timezone))).minusDays(1)
        val run = reconciliation.run(date)
        audit.record(ActorType.AGENT, me.id, "RECONCILIATION_RUN", "RECONCILIATION_RUN", run.id, "$date : ${run.discrepancies} écart(s)")
        return run
    }

    @GetMapping("/reconciliation/items")
    fun items(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @RequestParam(defaultValue = "true") open: Boolean,
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<ReconciliationItem> {
        requireAccess(me)
        return reconciliation.items(open, limit.coerceIn(1, 1000))
    }

    @PostMapping("/reconciliation/items/{id}/resolve")
    fun resolveItem(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody body: ResolveRequest,
    ): ReconciliationItem {
        requireAccess(me)
        val item = reconciliation.resolveItem(me.id, id, body.action == ResolveAction.CREDIT, body.note.trim())
        audit.record(ActorType.AGENT, me.id, "RECONCILIATION_${body.action}", item.type.name, item.targetId, "${item.kind} : ${body.note.trim()}")
        return item
    }

    /** Retrait à vérifier : PAID le note versé (l'opérateur doit le confirmer), REFUND rend l'argent (l'opérateur doit confirmer l'échec). */
    @PostMapping("/withdrawals/{id}/resolve")
    fun resolveWithdrawal(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody body: WithdrawalResolveRequest,
    ): Withdrawal {
        requireAccess(me)
        val w = withdrawals.resolve(id, body.action, body.note.trim())
        audit.record(ActorType.AGENT, me.id, "WITHDRAWAL_REVIEW_${body.action}", "WITHDRAWAL", id, "${w.amountMinor} ${w.currency} : ${body.note.trim()}")
        return w
    }

    @GetMapping("/topups/review")
    fun reviewQueue(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): List<ReviewTopup> {
        requireAccess(me)
        return reconciliation.reviewQueue()
    }

    /** Recharge à vérifier : CREDIT relit le statut et crédite le montant encaissé ; CLOSE la clôt en échec. */
    @PostMapping("/topups/{id}/resolve")
    fun resolveTopup(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody body: ResolveRequest,
    ): Topup {
        requireAccess(me)
        val topup = topups.resolve(id, body.action == ResolveAction.CREDIT, body.note.trim(), setOf(TopupStatus.REVIEW))
        val action = if (body.action == ResolveAction.CREDIT) "TOPUP_REVIEW_CREDITED" else "TOPUP_REVIEW_CLOSED"
        audit.record(ActorType.AGENT, me.id, action, "TOPUP", id, "${topup.amountMinor} ${topup.currency} : ${body.note.trim()}")
        return topup
    }
}
