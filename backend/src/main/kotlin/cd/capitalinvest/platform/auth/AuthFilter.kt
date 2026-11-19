package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.common.BusinessException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

const val CUSTOMER_ID_ATTRIBUTE = "authenticatedCustomerId"

/**
 * Toute route /v1 exige un jeton « Authorization: Bearer … », sauf l'inscription, la connexion,
 * le PIN oublié et les simulateurs de développement.
 */
@Component
class AuthFilter(private val auth: AuthService, private val json: ObjectMapper) : OncePerRequestFilter() {

    private val publicPrefixes = listOf("/v1/auth/otp", "/v1/auth/register", "/v1/auth/login", "/v1/auth/pin-reset", "/v1/simulators/", "/v1/webhooks/")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        // Le back-office a son propre filtre et ses propres jetons.
        return !path.startsWith("/v1/") || path.startsWith("/v1/backoffice/") || publicPrefixes.any { path.startsWith(it) }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, "AUTH_REQUIRED", "Connexion requise")
        }
        try {
            request.setAttribute(CUSTOMER_ID_ATTRIBUTE, auth.authenticate(header.removePrefix("Bearer ").trim()))
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
