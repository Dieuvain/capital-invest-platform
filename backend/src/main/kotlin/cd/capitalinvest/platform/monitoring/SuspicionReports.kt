package cd.capitalinvest.platform.monitoring

import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryItem
import cd.capitalinvest.platform.history.HistoryQuery
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.ledger.LedgerRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

enum class ReportStatus { DRAFT, SUBMITTED }

/** Ligne de la liste des déclarations. */
data class SuspicionReportSummary(
    val id: UUID,
    val status: ReportStatus,
    val customerId: UUID,
    val customerName: String,
    val customerPhone: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val createdByName: String,
    val createdAt: OffsetDateTime,
    val submittedOn: LocalDate?,
    val cenarefReference: String?,
)

/** Identité du client telle que connue de Capital Invest au moment de la déclaration. */
data class ReportSubject(
    val customerId: UUID,
    val fullName: String,
    val phone: String,
    val kycLevel: Int,
    val status: String,
    val openedAt: OffsetDateTime,
    val balanceMinor: Long,
    /** Pièce d'identité vérifiée (niveau 2), sinon null. */
    val idType: String?,
    val idNumber: String?,
    val birthDate: LocalDate?,
)

data class ReportTotals(val count: Int, val inMinor: Long, val outMinor: Long, val truncated: Boolean)

/** Le dossier complet : de quoi rédiger, relire et imprimer la déclaration. */
data class SuspicionReport(
    val id: UUID,
    val status: ReportStatus,
    val subject: ReportSubject,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val facts: String,
    val analysis: String,
    val alerts: List<MonitoringAlert>,
    val operations: List<HistoryItem>,
    val totals: ReportTotals,
    val createdByName: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val submittedByName: String?,
    val submittedOn: LocalDate?,
    val cenarefReference: String?,
)

/**
 * Déclarations de soupçon. Capital Invest ne transmet rien lui-même : la conformité rédige le dossier ici,
 * le transmet à la CENAREF par le canal officiel, puis note la date et la référence reçue. Le dossier est
 * alors figé. Réservé à la conformité et aux administrateurs ; chaque étape est au journal d'audit.
 */
@Service
class SuspicionReportService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val history: HistoryService,
    private val monitoring: MonitoringService,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val zone = ZoneId.of("Africa/Lubumbashi")
    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    private fun today() = LocalDate.now(clock.withZone(zone))

    private data class Row(
        val id: UUID, val customerId: UUID, val alertId: UUID?, val status: ReportStatus, val from: LocalDate, val to: LocalDate,
        val facts: String, val analysis: String, val createdBy: String, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime,
        val submittedBy: String?, val submittedOn: LocalDate?, val reference: String?,
    )

    /** Ouvre un brouillon : 30 derniers jours, faits pré-remplis avec les alertes du client. */
    @Transactional
    fun create(agentId: UUID, customerId: UUID, alertId: UUID?): SuspicionReport {
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val alert = alertId?.let { monitoring.get(it) }
        if (alert != null && alert.customerId != customerId) throw BusinessException("ALERT_MISMATCH", "Cette alerte concerne un autre client")
        val to = today()
        val from = (alert?.createdAt?.atZoneSameInstant(zone)?.toLocalDate() ?: to).minusDays(30)
        val alerts = monitoring.list("ALL", customerId)
        val facts = alerts.joinToString("\n") { "- ${it.ruleLabel} (${it.createdAt.atZoneSameInstant(zone).format(DAY)}) : ${it.detail}" }
        val id = UUID.randomUUID()
        val now = now()
        jdbc.sql(
            """INSERT INTO suspicion_report (id, customer_id, alert_id, period_from, period_to, facts, created_by, created_at, updated_at)
               VALUES (:id, :c, :a, :from, :to, :facts, :by, :now, :now)""",
        ).param("id", id).param("c", customer.id).param("a", alertId).param("from", from).param("to", to)
            .param("facts", facts).param("by", agentId).param("now", now).update()
        audit.record(ActorType.AGENT, agentId, "SUSPICION_REPORT_DRAFTED", "CUSTOMER", customerId)
        return get(id)
    }

    @Transactional
    fun update(agentId: UUID, id: UUID, from: LocalDate, to: LocalDate, facts: String, analysis: String): SuspicionReport {
        val r = row(id, lock = true)
        if (r.status != ReportStatus.DRAFT) throw BusinessException("REPORT_SUBMITTED", "Déclaration déjà transmise : elle ne change plus")
        if (to.isBefore(from)) throw BusinessException("INVALID_PERIOD", "La date de fin est avant la date de début")
        jdbc.sql("UPDATE suspicion_report SET period_from = :from, period_to = :to, facts = :f, analysis = :a, updated_at = :now WHERE id = :id")
            .param("from", from).param("to", to).param("f", facts.trim()).param("a", analysis.trim()).param("now", now()).param("id", id).update()
        return get(id)
    }

    /** Note la transmission à la CENAREF. Les faits et l'analyse doivent être rédigés. */
    @Transactional
    fun submit(agentId: UUID, id: UUID, submittedOn: LocalDate, reference: String): SuspicionReport {
        val r = row(id, lock = true)
        if (r.status != ReportStatus.DRAFT) throw BusinessException("REPORT_SUBMITTED", "Déclaration déjà transmise")
        if (r.facts.isBlank() || r.analysis.isBlank()) {
            throw BusinessException("REPORT_INCOMPLETE", "Rédigez les faits et l'analyse avant de noter la transmission")
        }
        if (submittedOn.isAfter(today())) throw BusinessException("INVALID_DATE", "La date de transmission est dans le futur")
        jdbc.sql(
            """UPDATE suspicion_report SET status = 'SUBMITTED', submitted_by = :by, submitted_at = :now, submitted_on = :on,
                      cenaref_reference = :ref, updated_at = :now WHERE id = :id""",
        ).param("by", agentId).param("now", now()).param("on", submittedOn).param("ref", reference.trim()).param("id", id).update()
        audit.record(ActorType.AGENT, agentId, "SUSPICION_REPORT_SUBMITTED", "CUSTOMER", r.customerId, "Référence CENAREF ${reference.trim()}")
        return get(id)
    }

    fun list(): List<SuspicionReportSummary> = jdbc.sql(
        """SELECT r.*, c.full_name AS customer_name, c.phone, a.full_name AS author FROM suspicion_report r
           JOIN customer c ON c.id = r.customer_id JOIN backoffice_agent a ON a.id = r.created_by
           ORDER BY (r.status = 'DRAFT') DESC, r.created_at DESC LIMIT 200""",
    ).query { rs, _ ->
        SuspicionReportSummary(
            rs.getObject("id", UUID::class.java), ReportStatus.valueOf(rs.getString("status")), rs.getObject("customer_id", UUID::class.java),
            rs.getString("customer_name"), rs.getString("phone"), rs.getObject("period_from", LocalDate::class.java),
            rs.getObject("period_to", LocalDate::class.java), rs.getString("author"), rs.getObject("created_at", OffsetDateTime::class.java),
            rs.getObject("submitted_on", LocalDate::class.java), rs.getString("cenaref_reference"),
        )
    }.list()

    /** Le dossier complet. Chaque ouverture est tracée : ces dossiers sont confidentiels. */
    fun open(agentId: UUID, id: UUID): SuspicionReport {
        val report = get(id)
        audit.record(ActorType.AGENT, agentId, "SUSPICION_REPORT_VIEWED", "CUSTOMER", report.subject.customerId)
        return report
    }

    private fun get(id: UUID): SuspicionReport {
        val r = row(id, lock = false)
        val c = customers.findById(r.customerId)!!
        val wallet = ledger.findCustomerWallet(c.id, Currency.USD)
        val kyc = jdbc.sql(
            """SELECT id_type, id_number, birth_date FROM kyc_request WHERE customer_id = :c AND status = 'APPROVED'
               ORDER BY decided_at DESC NULLS LAST LIMIT 1""",
        ).param("c", c.id).query { rs, _ ->
            Triple(rs.getString("id_type"), rs.getString("id_number"), rs.getObject("birth_date", LocalDate::class.java))
        }.list().firstOrNull()
        val openedAt = jdbc.sql("SELECT created_at FROM customer WHERE id = :c").param("c", c.id).query(OffsetDateTime::class.java).single()
        val subject = ReportSubject(
            c.id, c.fullName, c.phone, c.kycLevel, c.status, openedAt, wallet?.let { ledger.balance(it.id) } ?: 0,
            kyc?.first, kyc?.second, kyc?.third,
        )
        // Toutes les opérations de la période, 500 au plus : au-delà, la CENAREF demandera l'extrait complet.
        val ops = mutableListOf<HistoryItem>()
        var cursor: String? = null
        do {
            val page = history.page(c.id, HistoryQuery(from = r.from, to = r.to, cursor = cursor, limit = 100), maskPhones = false)
            ops += page.items
            cursor = page.nextCursor
        } while (cursor != null && ops.size < MAX_OPERATIONS)
        val shown = ops.take(MAX_OPERATIONS)
        val totals = ReportTotals(
            shown.size, shown.filter { it.netMinor > 0 }.sumOf { it.netMinor }, shown.filter { it.netMinor < 0 }.sumOf { -it.netMinor },
            truncated = cursor != null || ops.size > MAX_OPERATIONS,
        )
        val alerts = monitoring.list("ALL", c.id)
        return SuspicionReport(
            r.id, r.status, subject, r.from, r.to, r.facts, r.analysis, alerts, shown, totals, r.createdBy, r.createdAt, r.updatedAt,
            r.submittedBy, r.submittedOn, r.reference,
        )
    }

    private fun row(id: UUID, lock: Boolean): Row = jdbc.sql(
        """SELECT r.*, a.full_name AS author, s.full_name AS submitter FROM suspicion_report r
           JOIN backoffice_agent a ON a.id = r.created_by LEFT JOIN backoffice_agent s ON s.id = r.submitted_by
           WHERE r.id = :id ${if (lock) "FOR UPDATE OF r" else ""}""",
    ).param("id", id).query(::map).optional().orElse(null) ?: throw NotFoundException("REPORT_NOT_FOUND", "Déclaration introuvable")

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Row(
        rs.getObject("id", UUID::class.java), rs.getObject("customer_id", UUID::class.java), rs.getObject("alert_id", UUID::class.java),
        ReportStatus.valueOf(rs.getString("status")), rs.getObject("period_from", LocalDate::class.java),
        rs.getObject("period_to", LocalDate::class.java), rs.getString("facts"), rs.getString("analysis"), rs.getString("author"),
        rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("updated_at", OffsetDateTime::class.java),
        rs.getString("submitter"), rs.getObject("submitted_on", LocalDate::class.java), rs.getString("cenaref_reference"),
    )

    companion object {
        const val MAX_OPERATIONS = 500
    }
}

data class CreateReportRequest(val customerId: UUID, val alertId: UUID? = null)

data class UpdateReportRequest(
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    @field:Size(max = 8000) val facts: String,
    @field:Size(max = 8000) val analysis: String,
)

data class SubmitReportRequest(val submittedOn: LocalDate, @field:NotBlank @field:Size(max = 100) val cenarefReference: String)

@RestController
@RequestMapping("/v1/backoffice/suspicion-reports")
class SuspicionReportController(private val reports: SuspicionReportService) {

    private fun requireCompliance(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    @GetMapping
    fun list(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): List<SuspicionReportSummary> {
        requireCompliance(me)
        return reports.list()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestBody b: CreateReportRequest): SuspicionReport {
        requireCompliance(me)
        return reports.create(me.id, b.customerId, b.alertId)
    }

    @GetMapping("/{id}")
    fun get(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): SuspicionReport {
        requireCompliance(me)
        return reports.open(me.id, id)
    }

    @PutMapping("/{id}")
    fun update(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: UpdateReportRequest): SuspicionReport {
        requireCompliance(me)
        return reports.update(me.id, id, b.periodFrom, b.periodTo, b.facts, b.analysis)
    }

    @PostMapping("/{id}/submit")
    fun submit(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: SubmitReportRequest): SuspicionReport {
        requireCompliance(me)
        return reports.submit(me.id, id, b.submittedOn, b.cenarefReference)
    }
}
