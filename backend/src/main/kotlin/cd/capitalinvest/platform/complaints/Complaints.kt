package cd.capitalinvest.platform.complaints

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryItem
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.notifications.NotificationService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

@ConfigurationProperties("capital-invest.complaints")
data class ComplaintsProperties(
    /** Délai de réponse promis au client. À aligner sur l'exigence de la BCC retenue par la conformité. */
    val responseTime: Duration = Duration.ofDays(7),
    /** Réclamations ouvertes en même temps par un client : au-delà, il attend les réponses. */
    val maxOpenPerCustomer: Int = 5,
)

enum class ComplaintCategory(val label: String) {
    NOT_RECEIVED("L'argent n'est pas arrivé"),
    WRONG_AMOUNT("Mauvais montant ou frais"),
    NOT_ME("Je n'ai pas fait cette opération"),
    OTHER("Autre problème"),
}

enum class ComplaintStatus { OPEN, ANSWERED }

/** FOUNDED : le client avait raison (une correction suit, par ajustement). UNFOUNDED : l'opération était correcte. */
enum class ComplaintOutcome { FOUNDED, UNFOUNDED }

data class Complaint(
    val id: UUID,
    /** Numéro à rappeler au service client, par ex. REC-20261119-3F9A2C. */
    val reference: String,
    val category: ComplaintCategory,
    val categoryLabel: String,
    val message: String,
    val status: ComplaintStatus,
    /** L'opération visée, telle que son reçu la montre. Absente pour une réclamation sur le compte. */
    val operation: HistoryItem?,
    val dueAt: OffsetDateTime,
    val answer: String?,
    val outcome: ComplaintOutcome?,
    val answeredAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

/** Une réclamation vue du back-office : le client, l'échéance et qui a répondu. */
data class BackofficeComplaint(
    val complaint: Complaint,
    val customerId: UUID,
    val customerName: String,
    val customerPhone: String,
    /** Échéance passée sans réponse. */
    val overdue: Boolean,
    /** « Je n'ai pas fait cette opération » : peut-être un vol de compte, à traiter d'abord. */
    val urgent: Boolean,
    val answeredByName: String?,
)

data class ComplaintRequest(
    val transactionId: UUID? = null,
    val category: ComplaintCategory,
    @field:NotBlank @field:Size(max = 1000) val message: String,
)

data class ComplaintAnswerRequest(
    val outcome: ComplaintOutcome,
    @field:NotBlank @field:Size(max = 1000) val answer: String,
)

/**
 * Réclamations des clients. Le client la dépose depuis le reçu de l'opération (ou pour son compte en général),
 * reçoit un numéro et une échéance ; un agent de la conformité répond, et la réponse lui parvient par SMS et dans l'app.
 */
@Service
class ComplaintService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val history: HistoryService,
    private val notifications: NotificationService,
    private val props: ComplaintsProperties,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    @Transactional
    fun open(customerId: UUID, transactionId: UUID?, category: ComplaintCategory, message: String): Complaint {
        // Verrou sur le client : deux envois simultanés ne dépassent pas le nombre de réclamations ouvertes.
        jdbc.sql("SELECT id FROM customer WHERE id = :c FOR UPDATE").param("c", customerId).query(UUID::class.java).list()
        // L'opération doit toucher le portefeuille du client : sinon, elle n'existe pas pour lui.
        val operation = transactionId?.let { history.receipt(customerId, it, maskPhones = true).item }
        if (transactionId != null) {
            jdbc.sql("SELECT id FROM complaint WHERE customer_id = :c AND transaction_id = :tx AND status = 'OPEN'")
                .param("c", customerId).param("tx", transactionId).query(UUID::class.java).list().firstOrNull()
                ?.let { throw ConflictException("COMPLAINT_ALREADY_OPEN", "Une réclamation est déjà ouverte pour cette opération") }
        }
        val open = jdbc.sql("SELECT count(*) FROM complaint WHERE customer_id = :c AND status = 'OPEN'")
            .param("c", customerId).query(Int::class.java).single()
        if (open >= props.maxOpenPerCustomer) {
            throw BusinessException("COMPLAINT_LIMIT", "Vous avez déjà ${props.maxOpenPerCustomer} réclamations en attente de réponse")
        }
        val id = UUID.randomUUID()
        val now = now()
        val due = now.plus(props.responseTime)
        jdbc.sql(
            """INSERT INTO complaint (id, customer_id, transaction_id, category, message, status, due_at, created_at)
               VALUES (:id, :c, :tx, :cat, :msg, 'OPEN', :due, :now)""",
        ).param("id", id).param("c", customerId).param("tx", transactionId).param("cat", category.name)
            .param("msg", message.trim()).param("due", due).param("now", now).update()
        val reference = reference(id, now)
        notifications.complaintReceived(customerId, id, reference, due.atZoneSameInstant(ZoneId.of(limits.timezone)).toLocalDate(), transactionId)
        return Complaint(id, reference, category, category.label, message.trim(), ComplaintStatus.OPEN, operation, due, null, null, null, now)
    }

    fun list(customerId: UUID): List<Complaint> =
        rows("c.customer_id = :c ORDER BY c.created_at DESC LIMIT 50") { it.param("c", customerId) }.map { it.toComplaint(maskPhones = true) }

    fun get(customerId: UUID, id: UUID): Complaint =
        rows("c.id = :id AND c.customer_id = :c") { it.param("id", id).param("c", customerId) }.firstOrNull()?.toComplaint(maskPhones = true)
            ?: throw NotFoundException("COMPLAINT_NOT_FOUND", "Réclamation introuvable")

    /** File du back-office : les ouvertes d'abord (urgentes, puis par échéance), puis les dernières réponses. */
    fun queue(status: ComplaintStatus): List<BackofficeComplaint> {
        val now = now()
        val order = if (status == ComplaintStatus.OPEN) "(c.category = 'NOT_ME') DESC, c.due_at" else "c.answered_at DESC"
        return rows("c.status = :s ORDER BY $order LIMIT 200") { it.param("s", status.name) }.map { r -> r.toBackoffice(now) }
    }

    fun forCustomer(customerId: UUID): List<BackofficeComplaint> {
        val now = now()
        return rows("c.customer_id = :c ORDER BY c.created_at DESC LIMIT 50") { it.param("c", customerId) }.map { r -> r.toBackoffice(now) }
    }

    @Transactional
    fun answer(agentId: UUID, id: UUID, outcome: ComplaintOutcome, answer: String): BackofficeComplaint {
        val r = rows("c.id = :id FOR UPDATE OF c") { it.param("id", id) }.firstOrNull()
            ?: throw NotFoundException("COMPLAINT_NOT_FOUND", "Réclamation introuvable")
        if (r.status != ComplaintStatus.OPEN) throw ConflictException("COMPLAINT_ALREADY_ANSWERED", "Cette réclamation a déjà reçu une réponse")
        val now = now()
        jdbc.sql(
            """UPDATE complaint SET status = 'ANSWERED', answer = :a, outcome = :o, answered_by = :agent, answered_at = :now WHERE id = :id""",
        ).param("a", answer.trim()).param("o", outcome.name).param("agent", agentId).param("now", now).param("id", id).update()
        notifications.complaintAnswered(r.customerId, id, reference(id, r.createdAt), r.transactionId)
        return rows("c.id = :id") { it.param("id", id) }.single().toBackoffice(now)
    }

    private class Row(
        val id: UUID, val customerId: UUID, val transactionId: UUID?, val category: ComplaintCategory, val message: String,
        val status: ComplaintStatus, val dueAt: OffsetDateTime, val answer: String?, val outcome: ComplaintOutcome?,
        val answeredAt: OffsetDateTime?, val answeredByName: String?, val createdAt: OffsetDateTime,
    )

    private fun row(rs: ResultSet) = Row(
        rs.getObject("id", UUID::class.java), rs.getObject("customer_id", UUID::class.java), rs.getObject("transaction_id", UUID::class.java),
        ComplaintCategory.valueOf(rs.getString("category")), rs.getString("message"), ComplaintStatus.valueOf(rs.getString("status")),
        rs.getObject("due_at", OffsetDateTime::class.java), rs.getString("answer"), rs.getString("outcome")?.let(ComplaintOutcome::valueOf),
        rs.getObject("answered_at", OffsetDateTime::class.java), rs.getString("answered_by_name"), rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun rows(where: String, bind: (JdbcClient.StatementSpec) -> JdbcClient.StatementSpec): List<Row> =
        bind(
            jdbc.sql(
                """SELECT c.*, a.full_name AS answered_by_name FROM complaint c LEFT JOIN backoffice_agent a ON a.id = c.answered_by
                   WHERE $where""",
            ),
        ).query { rs, _ -> row(rs) }.list()

    private fun Row.toComplaint(maskPhones: Boolean) = Complaint(
        id, reference(id, createdAt), category, category.label, message, status,
        transactionId?.let { history.receipt(customerId, it, maskPhones).item },
        dueAt, answer, outcome, answeredAt, createdAt,
    )

    private fun Row.toBackoffice(now: OffsetDateTime): BackofficeComplaint {
        val c = customers.findById(customerId)!!
        return BackofficeComplaint(
            toComplaint(maskPhones = false), customerId, c.fullName, c.phone,
            overdue = status == ComplaintStatus.OPEN && dueAt.isBefore(now),
            urgent = category == ComplaintCategory.NOT_ME,
            answeredByName = answeredByName,
        )
    }

    companion object {
        private val day = DateTimeFormatter.ofPattern("yyyyMMdd")

        fun reference(id: UUID, at: OffsetDateTime): String =
            "REC-${at.withOffsetSameInstant(ZoneOffset.UTC).format(day)}-${id.toString().replace("-", "").take(6).uppercase()}"
    }
}

@RestController
@RequestMapping("/v1/me/complaints")
class ComplaintController(private val complaints: ComplaintService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @Valid @RequestBody body: ComplaintRequest) =
        complaints.open(customerId, body.transactionId, body.category, body.message)

    @GetMapping
    fun list(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = complaints.list(customerId)

    @GetMapping("/{id}")
    fun get(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = complaints.get(customerId, id)
}

/** File des réclamations du back-office. Conformité et administrateurs, qui lisent aussi les opérations des clients. */
@RestController
@RequestMapping("/v1/backoffice")
class BackofficeComplaintController(private val complaints: ComplaintService, private val audit: AuditLog) {
    private fun requireAccess(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    @GetMapping("/complaints")
    fun queue(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestParam(defaultValue = "OPEN") status: ComplaintStatus): List<BackofficeComplaint> {
        requireAccess(me)
        return complaints.queue(status)
    }

    @GetMapping("/customers/{id}/complaints")
    fun forCustomer(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): List<BackofficeComplaint> {
        requireAccess(me)
        return complaints.forCustomer(id)
    }

    @PostMapping("/complaints/{id}/answer")
    fun answer(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody body: ComplaintAnswerRequest,
    ): BackofficeComplaint {
        requireAccess(me)
        val c = complaints.answer(me.id, id, body.outcome, body.answer)
        audit.record(ActorType.AGENT, me.id, "COMPLAINT_ANSWERED_${body.outcome}", "COMPLAINT", id, "${c.complaint.reference} : ${body.answer.trim()}")
        return c
    }
}
