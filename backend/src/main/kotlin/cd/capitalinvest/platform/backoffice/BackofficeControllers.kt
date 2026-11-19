package cd.capitalinvest.platform.backoffice

import cd.capitalinvest.platform.common.AuditEntry
import cd.capitalinvest.platform.common.AuditLog
import jakarta.validation.Valid
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class AgentLoginRequest(@field:Email val email: String, @field:NotBlank val password: String)

data class CreateAgentRequest(
    @field:Email val email: String,
    @field:NotBlank @field:Size(max = 120) val fullName: String,
    val password: String,
    val role: AgentRole,
)

@RestController
@RequestMapping("/v1/backoffice")
class BackofficeAuthController(private val agents: AgentService, private val auditLog: AuditLog) {

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody b: AgentLoginRequest): AgentTokens = agents.login(b.email, b.password)

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestHeader("Authorization") header: String) = agents.logout(header.removePrefix("Bearer ").trim())

    /** Création d'un agent, réservée aux administrateurs. */
    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @Valid @RequestBody b: CreateAgentRequest): AgentView {
        if (me.role != AgentRole.ADMIN) throw ForbiddenException("ADMIN_ONLY", "Réservé aux administrateurs")
        return agents.create(me.id, b.email, b.fullName, b.password, b.role)
    }

    /** L'agent connecté : l'écran s'en sert pour retrouver sa session et adapter les menus à son rôle. */
    @GetMapping("/me")
    fun me(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): AgentView = agents.find(me.id)

    @GetMapping("/agents")
    fun list(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): List<AgentView> {
        if (me.role != AgentRole.ADMIN) throw ForbiddenException("ADMIN_ONLY", "Réservé aux administrateurs")
        return agents.list()
    }

    @PostMapping("/agents/{id}/disable")
    fun disable(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID) = agents.setActive(me, id, false)

    @PostMapping("/agents/{id}/enable")
    fun enable(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID) = agents.setActive(me, id, true)

    /** Journal d'audit, le plus récent d'abord. Réservé à la conformité et aux administrateurs. */
    @GetMapping("/audit")
    fun audit(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<AuditEntry> {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
        return auditLog.recent(limit.coerceIn(1, 500))
    }
}

/** Autorise l'écran back-office à appeler l'API depuis son propre domaine, s'il en a un. */
@Configuration
class BackofficeCors(private val props: BackofficeProperties) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        if (props.allowedOrigins.isEmpty()) return
        registry.addMapping("/v1/backoffice/**")
            .allowedOrigins(*props.allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT")
            .allowedHeaders("Authorization", "Content-Type")
            .maxAge(3600)
    }
}
