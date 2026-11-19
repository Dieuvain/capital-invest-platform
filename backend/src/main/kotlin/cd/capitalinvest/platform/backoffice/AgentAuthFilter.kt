package cd.capitalinvest.platform.backoffice

import cd.capitalinvest.platform.common.BusinessException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

const val AGENT_ATTRIBUTE = "authenticatedAgent"

/** Les routes /v1/backoffice exigent un jeton d'agent, distinct des jetons clients. */
@Component
class AgentAuthFilter(private val agents: AgentService, private val json: ObjectMapper) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        // La pré-vérification CORS du navigateur (OPTIONS) n'a jamais de jeton.
        if (request.method == "OPTIONS") return true
        return !path.startsWith("/v1/backoffice/") || path == "/v1/backoffice/auth/login"
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) return reject(response, "AUTH_REQUIRED", "Connexion agent requise")
        try {
            request.setAttribute(AGENT_ATTRIBUTE, agents.authenticate(header.removePrefix("Bearer ").trim()))
        } catch (e: BusinessException) {
            return reject(response, e.code, e.message ?: e.code)
        }
        chain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse, code: String, detail: String) {
        response.status = 401
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(json.writeValueAsString(mapOf("status" to 401, "title" to code, "code" to code, "detail" to detail)))
    }
}
