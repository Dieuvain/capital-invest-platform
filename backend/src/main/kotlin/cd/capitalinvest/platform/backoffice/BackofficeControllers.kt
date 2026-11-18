package cd.capitalinvest.platform.backoffice

import jakarta.validation.Valid
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
class BackofficeAuthController(private val agents: AgentService) {

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
}
