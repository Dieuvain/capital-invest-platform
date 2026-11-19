package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.history.HistoryService
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.notifications.NotificationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

@ConfigurationProperties("capital-invest.scheduled-transfers")
data class ScheduledTransfersProperties(
    /** Heure d'envoi, heure de Lubumbashi : le matin, quand le client peut encore recharger s'il manque de l'argent. */
    val runAt: LocalTime = LocalTime.of(8, 0),
    val maxActivePerCustomer: Int = 10,
    /** Après autant d'échecs d'affilée, l'envoi est mis en pause et le client prévenu. */
    val pauseAfterFailures: Int = 3,
    /** Désactivé dans les tests, qui appellent eux-mêmes [ScheduledTransferService.runDue]. */
    val pollerEnabled: Boolean = true,
)

enum class TransferFrequency { WEEKLY, MONTHLY }

enum class ScheduledTransferStatus { ACTIVE, PAUSED, CANCELLED }

data class ScheduledTransfer(
    val id: UUID,
    val recipientName: String,
    /** Numéro du destinataire, masqué côté app. */
    val recipientPhone: String,
    val amountMinor: Long,
    /** Frais prélevés à chaque envoi, au tarif d'aujourd'hui. */
    val feeMinor: Long,
    val note: String?,
    val frequency: TransferFrequency,
    val day: Int,
    val status: ScheduledTransferStatus,
    /** Prochain envoi (absent si en pause). */
    val nextRunAt: OffsetDateTime?,
    val lastRunAt: OffsetDateTime?,
    /** SENT ou FAILED. */
    val lastResult: String?,
    /** Pourquoi le dernier envoi n'est pas parti, en clair. */
    val lastFailure: String?,
    val createdAt: OffsetDateTime,
)

data class ScheduledTransferRequest(
    val toPhone: String,
    @field:Positive val amountMinor: Long,
    @field:Size(max = 140) val note: String? = null,
    val frequency: TransferFrequency,
    @field:Min(1) @field:Max(31) val day: Int,
    /** Le PIN autorise l'envoi une fois pour toutes les échéances. */
    val pin: String,
)

/**
 * Envois programmés : le client confirme une fois par son PIN, puis l'envoi part chaque semaine ou chaque mois à 8 h.
 * Chaque échéance est un envoi ordinaire (frais, plafonds, délai après changement de PIN) avec sa propre clé d'idempotence :
 * une échéance ne part jamais deux fois, même si deux serveurs la traitent en même temps.
 */
@Service
class ScheduledTransferService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val transfers: TransferService,
    private val fees: FeeService,
    private val notifications: NotificationService,
    private val props: ScheduledTransfersProperties,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone get() = ZoneId.of(limits.timezone)

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** Prochaine échéance strictement après [after] (heure locale). Le 31 devient le dernier jour d'un mois plus court. */
    fun nextRun(frequency: TransferFrequency, day: Int, after: OffsetDateTime): OffsetDateTime {
        val local = after.atZoneSameInstant(zone)
        var date = when (frequency) {
            TransferFrequency.WEEKLY -> local.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)))
            TransferFrequency.MONTHLY -> monthDay(local.toLocalDate(), day)
        }
        while (!date.atTime(props.runAt).atZone(zone).isAfter(local)) {
            date = when (frequency) {
                TransferFrequency.WEEKLY -> date.plusWeeks(1)
                TransferFrequency.MONTHLY -> monthDay(date.plusMonths(1), day)
            }
        }
        return date.atTime(props.runAt).atZone(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC)
    }

    private fun monthDay(inMonth: LocalDate, day: Int): LocalDate = inMonth.withDayOfMonth(minOf(day, inMonth.lengthOfMonth()))

    @Transactional
    fun create(customerId: UUID, r: ScheduledTransferRequest): ScheduledTransfer {
        if (r.frequency == TransferFrequency.WEEKLY && r.day > 7) throw BusinessException("INVALID_DAY", "Choisissez un jour de la semaine")
        jdbc.sql("SELECT id FROM customer WHERE id = :c FOR UPDATE").param("c", customerId).query(UUID::class.java).list()
        val recipient = normalizeDrcPhone(r.toPhone)?.let(customers::findByPhone)
            ?: throw NotFoundException("RECIPIENT_NOT_FOUND", "Aucun client Capital Invest avec ce numéro")
        if (recipient.id == customerId) throw BusinessException("SAME_ACCOUNT", "Impossible d'envoyer à soi-même")
        val active = jdbc.sql("SELECT count(*) FROM scheduled_transfer WHERE customer_id = :c AND status <> 'CANCELLED'")
            .param("c", customerId).query(Int::class.java).single()
        if (active >= props.maxActivePerCustomer) {
            throw BusinessException("SCHEDULED_TRANSFER_LIMIT", "Vous avez déjà ${props.maxActivePerCustomer} envois programmés")
        }
        val id = UUID.randomUUID()
        val now = now()
        jdbc.sql(
            """INSERT INTO scheduled_transfer (id, customer_id, recipient_id, amount_minor, note, frequency, day, status, next_run_at, created_at)
               VALUES (:id, :c, :r, :a, :note, :f, :d, 'ACTIVE', :next, :now)""",
        ).param("id", id).param("c", customerId).param("r", recipient.id).param("a", r.amountMinor)
            .param("note", r.note?.trim()?.ifEmpty { null }).param("f", r.frequency.name).param("d", r.day)
            .param("next", nextRun(r.frequency, r.day, now)).param("now", now).update()
        return get(customerId, id, maskPhone = true)
    }

    fun list(customerId: UUID, maskPhone: Boolean): List<ScheduledTransfer> =
        rows("s.customer_id = :c AND s.status <> 'CANCELLED' ORDER BY s.created_at") { it.param("c", customerId) }.map { it.view(maskPhone) }

    fun get(customerId: UUID, id: UUID, maskPhone: Boolean): ScheduledTransfer =
        rows("s.id = :id AND s.customer_id = :c AND s.status <> 'CANCELLED'") { it.param("id", id).param("c", customerId) }
            .firstOrNull()?.view(maskPhone) ?: throw NotFoundException("SCHEDULED_TRANSFER_NOT_FOUND", "Envoi programmé introuvable")

    @Transactional
    fun pause(customerId: UUID, id: UUID): ScheduledTransfer {
        get(customerId, id, true)
        jdbc.sql("UPDATE scheduled_transfer SET status = 'PAUSED' WHERE id = :id").param("id", id).update()
        return get(customerId, id, true)
    }

    /** Reprise : la prochaine échéance est recalculée à partir d'aujourd'hui, sans rattraper celles manquées. */
    @Transactional
    fun resume(customerId: UUID, id: UUID): ScheduledTransfer {
        val s = get(customerId, id, true)
        jdbc.sql("UPDATE scheduled_transfer SET status = 'ACTIVE', failures = 0, next_run_at = :next WHERE id = :id")
            .param("next", nextRun(s.frequency, s.day, now())).param("id", id).update()
        return get(customerId, id, true)
    }

    @Transactional
    fun cancel(customerId: UUID, id: UUID) {
        get(customerId, id, true)
        jdbc.sql("UPDATE scheduled_transfer SET status = 'CANCELLED' WHERE id = :id").param("id", id).update()
    }

    /** Envoie les échéances arrivées. Chaque envoi a sa transaction : un échec n'empêche pas les autres. */
    fun runDue(): Int {
        val due = jdbc.sql("SELECT id FROM scheduled_transfer WHERE status = 'ACTIVE' AND next_run_at <= :now ORDER BY next_run_at LIMIT 500")
            .param("now", now()).query(UUID::class.java).list()
        due.forEach { id -> runCatching { runOne(id) }.onFailure { log.error("Envoi programmé {} : {}", id, it.message, it) } }
        return due.size
    }

    private fun runOne(id: UUID) {
        val r = rows("s.id = :id AND s.status = 'ACTIVE'") { it.param("id", id) }.firstOrNull() ?: return
        val occurrence = r.nextRunAt.atZoneSameInstant(zone).toLocalDate()
        val recipientPhone = r.recipientPhone
        val failure = try {
            transfers.transfer("scheduled:$id:$occurrence", r.customerId, recipientPhone, r.amountMinor, r.note ?: "Envoi programmé")
            null
        } catch (e: BusinessException) {
            e
        }
        val next = nextRun(r.frequency, r.day, r.nextRunAt)
        if (failure == null) {
            jdbc.sql(
                """UPDATE scheduled_transfer SET next_run_at = :next, last_run_at = :now, last_result = 'SENT', last_failure = NULL, failures = 0
                   WHERE id = :id AND next_run_at = :was""",
            ).param("next", next).param("now", now()).param("id", id).param("was", r.nextRunAt).update()
            return
        }
        val failures = r.failures + 1
        val paused = failures >= props.pauseAfterFailures
        val reason = failureReason(failure)
        val updated = jdbc.sql(
            """UPDATE scheduled_transfer SET next_run_at = :next, last_run_at = :now, last_result = 'FAILED', last_failure = :reason,
                      failures = :failures, status = :status
               WHERE id = :id AND next_run_at = :was""",
        ).param("next", next).param("now", now()).param("reason", reason).param("failures", failures)
            .param("status", if (paused) "PAUSED" else "ACTIVE").param("id", id).param("was", r.nextRunAt).update()
        if (updated > 0) {
            notifications.scheduledTransferFailed(r.customerId, id, occurrence, r.recipientName, r.amountMinor, reason, paused)
        }
    }

    private fun failureReason(e: BusinessException): String = when (e.code) {
        "INSUFFICIENT_FUNDS" -> "Solde insuffisant"
        "LIMIT_DAILY" -> "Plafond journalier d'envoi atteint"
        "LIMIT_PER_TRANSACTION" -> "Montant au-dessus de votre plafond par opération"
        "PIN_RECENTLY_RESET" -> "Envois suspendus 24 h après un changement de PIN"
        "RECIPIENT_LIMIT_REACHED", "LIMIT_MAX_BALANCE" -> "Le destinataire ne peut pas recevoir ce montant"
        "CUSTOMER_NOT_ACTIVE" -> "Compte suspendu"
        else -> e.message ?: e.code
    }

    private class Row(
        val id: UUID, val customerId: UUID, val recipientName: String, val recipientPhone: String, val amountMinor: Long,
        val note: String?, val frequency: TransferFrequency, val day: Int, val status: ScheduledTransferStatus,
        val nextRunAt: OffsetDateTime, val lastRunAt: OffsetDateTime?, val lastResult: String?, val lastFailure: String?,
        val failures: Int, val createdAt: OffsetDateTime,
    )

    private fun row(rs: ResultSet) = Row(
        rs.getObject("id", UUID::class.java), rs.getObject("customer_id", UUID::class.java), rs.getString("full_name"),
        rs.getString("phone"), rs.getLong("amount_minor"), rs.getString("note"), TransferFrequency.valueOf(rs.getString("frequency")),
        rs.getInt("day"), ScheduledTransferStatus.valueOf(rs.getString("status")), rs.getObject("next_run_at", OffsetDateTime::class.java),
        rs.getObject("last_run_at", OffsetDateTime::class.java), rs.getString("last_result"), rs.getString("last_failure"),
        rs.getInt("failures"), rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun rows(where: String, bind: (JdbcClient.StatementSpec) -> JdbcClient.StatementSpec): List<Row> =
        bind(jdbc.sql("SELECT s.*, r.full_name, r.phone FROM scheduled_transfer s JOIN customer r ON r.id = s.recipient_id WHERE $where"))
            .query { rs, _ -> row(rs) }.list()

    private fun Row.view(maskPhone: Boolean) = ScheduledTransfer(
        id, recipientName, if (maskPhone) HistoryService.maskPhone(recipientPhone) else recipientPhone, amountMinor,
        fees.transferFee(amountMinor), note, frequency, day, status,
        nextRunAt.takeIf { status == ScheduledTransferStatus.ACTIVE }, lastRunAt, lastResult, lastFailure, createdAt,
    )
}

@Component
class ScheduledTransferJobs(private val service: ScheduledTransferService, private val props: ScheduledTransfersProperties) {
    @Scheduled(fixedDelayString = "\${capital-invest.scheduled-transfers.poll-delay-ms:60000}", initialDelay = 45_000)
    fun run() {
        if (props.pollerEnabled) service.runDue()
    }
}

@RestController
@RequestMapping("/v1/me/scheduled-transfers")
class ScheduledTransferController(private val service: ScheduledTransferService, private val auth: AuthService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @Valid @RequestBody body: ScheduledTransferRequest): ScheduledTransfer {
        // Vérifié dans sa propre transaction : un mauvais PIN reste compté.
        auth.confirmWithPin(customerId, body.pin)
        return service.create(customerId, body)
    }

    @GetMapping
    fun list(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = service.list(customerId, maskPhone = true)

    @PostMapping("/{id}/pause")
    fun pause(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = service.pause(customerId, id)

    @PostMapping("/{id}/resume")
    fun resume(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = service.resume(customerId, id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = service.cancel(customerId, id)
}

/** Fiche client du back-office : les envois programmés, pour répondre à « de l'argent part tout seul ». */
@RestController
@RequestMapping("/v1/backoffice/customers")
class BackofficeScheduledTransferController(private val service: ScheduledTransferService) {
    @GetMapping("/{id}/scheduled-transfers")
    fun list(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): List<ScheduledTransfer> {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
        return service.list(id, maskPhone = false)
    }
}
