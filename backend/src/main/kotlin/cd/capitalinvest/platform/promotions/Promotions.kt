package cd.capitalinvest.platform.promotions

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class PromotionAction { KYC, TOPUP, TRANSFER, LIMITS }
enum class PromotionStyle { OCHRE, INK, GREEN, SKY }
enum class PromotionAudience { ALL, KYC1, KYC2 }

/** Ce que l'app reçoit : juste de quoi afficher la carte et savoir où mène le bouton. */
data class PromotionCard(
    val id: UUID,
    val title: String,
    val body: String,
    val ctaLabel: String?,
    val action: PromotionAction?,
    val style: PromotionStyle,
)

/** Vue complète pour le back-office. */
data class PromotionView(
    val id: UUID,
    val title: String,
    val body: String,
    val ctaLabel: String?,
    val action: PromotionAction?,
    val style: PromotionStyle,
    val audience: PromotionAudience,
    val priority: Int,
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime?,
    val active: Boolean,
    val live: Boolean,
)

data class PromotionRequest(
    @field:NotBlank @field:Size(max = 60) val title: String,
    @field:NotBlank @field:Size(max = 140) val body: String,
    @field:Size(min = 1, max = 30) val ctaLabel: String? = null,
    val action: PromotionAction? = null,
    val style: PromotionStyle = PromotionStyle.OCHRE,
    val audience: PromotionAudience = PromotionAudience.ALL,
    val priority: Int = 0,
    val startsAt: OffsetDateTime? = null,
    val endsAt: OffsetDateTime? = null,
    val active: Boolean = true,
)

@Service
class PromotionService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    companion object {
        /** Au-delà, le bandeau devient trop long pour être lu. */
        const val MAX_SHOWN = 5
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /** Promotions en cours pour ce client : actives, dans leur période, et pour son niveau KYC. */
    fun forCustomer(customerId: UUID): List<PromotionCard> {
        val level = customers.findById(customerId)?.kycLevel ?: return emptyList()
        return jdbc.sql(
            """SELECT * FROM promotion
               WHERE active AND starts_at <= :now AND (ends_at IS NULL OR ends_at > :now)
                 AND audience IN ('ALL', :audience)
               ORDER BY priority DESC, starts_at DESC
               LIMIT :max""",
        ).param("now", now()).param("audience", "KYC$level").param("max", MAX_SHOWN)
            .query { rs, _ -> toView(rs) }.list()
            .map { PromotionCard(it.id, it.title, it.body, it.ctaLabel, it.action, it.style) }
    }

    fun list(): List<PromotionView> =
        jdbc.sql("SELECT * FROM promotion ORDER BY active DESC, priority DESC, created_at DESC").query { rs, _ -> toView(rs) }.list()

    @Transactional
    fun create(agent: AgentPrincipal, r: PromotionRequest): PromotionView {
        requireEditor(agent)
        validate(r)
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO promotion (id, title, body, cta_label, action, style, audience, priority, starts_at, ends_at, active)
               VALUES (:id, :title, :body, :cta, :action, :style, :audience, :priority, :starts, :ends, :active)""",
        ).params(params(id, r)).update()
        audit.record(ActorType.AGENT, agent.id, "PROMOTION_CREATED", "PROMOTION", id, r.title)
        return get(id)
    }

    @Transactional
    fun update(agent: AgentPrincipal, id: UUID, r: PromotionRequest): PromotionView {
        requireEditor(agent)
        validate(r)
        get(id)
        jdbc.sql(
            """UPDATE promotion SET title = :title, body = :body, cta_label = :cta, action = :action, style = :style,
                 audience = :audience, priority = :priority, starts_at = :starts, ends_at = :ends, active = :active,
                 updated_at = now()
               WHERE id = :id""",
        ).params(params(id, r)).update()
        audit.record(ActorType.AGENT, agent.id, if (r.active) "PROMOTION_UPDATED" else "PROMOTION_DISABLED", "PROMOTION", id, r.title)
        return get(id)
    }

    fun get(id: UUID): PromotionView =
        jdbc.sql("SELECT * FROM promotion WHERE id = :id").param("id", id).query { rs, _ -> toView(rs) }.list().firstOrNull()
            ?: throw NotFoundException("PROMOTION_NOT_FOUND", "Promotion introuvable")

    private fun requireEditor(agent: AgentPrincipal) {
        if (agent.role != AgentRole.ADMIN && agent.role != AgentRole.MARKETING) {
            throw ForbiddenException("MARKETING_ONLY", "Réservé au marketing et aux administrateurs")
        }
    }

    private fun validate(r: PromotionRequest) {
        if ((r.ctaLabel == null) != (r.action == null)) {
            throw BusinessException("PROMOTION_INVALID", "Un bouton a besoin d'un texte et d'une action", HttpStatus.UNPROCESSABLE_ENTITY)
        }
        val starts = r.startsAt ?: now()
        if (r.endsAt != null && !r.endsAt.isAfter(starts)) {
            throw BusinessException("PROMOTION_INVALID", "La fin doit être après le début", HttpStatus.UNPROCESSABLE_ENTITY)
        }
    }

    private fun params(id: UUID, r: PromotionRequest): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to r.title.trim(),
        "body" to r.body.trim(),
        "cta" to r.ctaLabel?.trim(),
        "action" to r.action?.name,
        "style" to r.style.name,
        "audience" to r.audience.name,
        "priority" to r.priority,
        "starts" to (r.startsAt ?: now()),
        "ends" to r.endsAt,
        "active" to r.active,
    )

    private fun toView(rs: ResultSet): PromotionView {
        val startsAt = rs.getObject("starts_at", OffsetDateTime::class.java)
        val endsAt = rs.getObject("ends_at", OffsetDateTime::class.java)
        val active = rs.getBoolean("active")
        val now = now()
        return PromotionView(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            body = rs.getString("body"),
            ctaLabel = rs.getString("cta_label"),
            action = rs.getString("action")?.let(PromotionAction::valueOf),
            style = PromotionStyle.valueOf(rs.getString("style")),
            audience = PromotionAudience.valueOf(rs.getString("audience")),
            priority = rs.getInt("priority"),
            startsAt = startsAt,
            endsAt = endsAt,
            active = active,
            live = active && !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now)),
        )
    }
}

@RestController
@RequestMapping("/v1/promotions")
class PromotionController(private val promotions: PromotionService) {
    @GetMapping
    fun list(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): List<PromotionCard> = promotions.forCustomer(customerId)
}

@RestController
@RequestMapping("/v1/backoffice/promotions")
class BackofficePromotionController(private val promotions: PromotionService) {
    @GetMapping
    fun list(): List<PromotionView> = promotions.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): PromotionView = promotions.get(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestAttribute(AGENT_ATTRIBUTE) agent: AgentPrincipal, @Valid @RequestBody b: PromotionRequest) =
        promotions.create(agent, b)

    /** Remplace la promotion. Pour la retirer, envoyer active=false : on garde l'historique. */
    @PutMapping("/{id}")
    fun update(@RequestAttribute(AGENT_ATTRIBUTE) agent: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: PromotionRequest) =
        promotions.update(agent, id, b)
}
