package cd.capitalinvest.platform.backoffice

import cd.capitalinvest.platform.auth.UnauthorizedException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

enum class AgentRole { KYC_AGENT, COMPLIANCE, ADMIN }

data class AgentPrincipal(val id: UUID, val role: AgentRole)

data class AgentView(val id: UUID, val email: String, val fullName: String, val role: AgentRole, val active: Boolean)

data class AgentTokens(val accessToken: String, val expiresInSeconds: Long, val agent: AgentView)

class ForbiddenException(code: String, message: String) : BusinessException(code, message, HttpStatus.FORBIDDEN)

@ConfigurationProperties("capital-invest.backoffice")
data class BackofficeProperties(
    /** Premier administrateur, créé au démarrage s'il n'existe aucun agent. */
    val bootstrapAdminEmail: String? = null,
    val bootstrapAdminPassword: String? = null,
    val passwordMinLength: Int = 12,
    val maxFailedAttempts: Int = 5,
    val lockMinutes: Long = 15,
    val sessionIdleMinutes: Long = 15,
    val sessionMaxHours: Long = 8,
)

@Service
class AgentService(
    private val jdbc: JdbcClient,
    private val props: BackofficeProperties,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val encoder = BCryptPasswordEncoder(12)
    private val random = SecureRandom()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun bootstrapAdmin() {
        val email = props.bootstrapAdminEmail?.takeIf { it.isNotBlank() } ?: return
        val password = props.bootstrapAdminPassword?.takeIf { it.isNotBlank() } ?: return
        val count = jdbc.sql("SELECT count(*) FROM backoffice_agent").query(Long::class.java).single()
        if (count == 0L) {
            create(null, email, "Administrateur", password, AgentRole.ADMIN)
            log.info("Premier administrateur back-office créé")
        }
    }

    @Transactional
    fun create(createdBy: UUID?, email: String, fullName: String, password: String, role: AgentRole): AgentView {
        if (password.length < props.passwordMinLength) {
            throw BusinessException("PASSWORD_TOO_SHORT", "Mot de passe de ${props.passwordMinLength} caractères au moins")
        }
        val normalized = email.trim().lowercase()
        val exists = jdbc.sql("SELECT count(*) FROM backoffice_agent WHERE email = :e").param("e", normalized).query(Long::class.java).single()
        if (exists > 0) throw ConflictException("AGENT_EMAIL_TAKEN", "Un agent existe déjà avec cette adresse")
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO backoffice_agent (id, email, full_name, password_hash, role)
               VALUES (:id, :e, :n, :h, :r)""",
        ).param("id", id).param("e", normalized).param("n", fullName.trim()).param("h", encoder.encode(password)).param("r", role.name).update()
        audit.record(if (createdBy == null) ActorType.SYSTEM else ActorType.AGENT, createdBy, "AGENT_CREATED", "AGENT", id, role.name)
        return AgentView(id, normalized, fullName.trim(), role, true)
    }

    /** Connexion par e-mail et mot de passe ; 5 erreurs bloquent le compte 15 minutes. */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun login(email: String, password: String): AgentTokens {
        val row = jdbc.sql(
            """SELECT id, email, full_name, password_hash, role, active, failed_attempts, locked_until
               FROM backoffice_agent WHERE email = :e FOR UPDATE""",
        ).param("e", email.trim().lowercase()).query { rs, _ ->
            Triple(
                AgentView(
                    rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("full_name"),
                    AgentRole.valueOf(rs.getString("role")), rs.getBoolean("active"),
                ),
                rs.getString("password_hash"),
                rs.getInt("failed_attempts") to rs.getObject("locked_until", OffsetDateTime::class.java),
            )
        }.list().firstOrNull()
        if (row == null) {
            encoder.matches(password, DUMMY_HASH) // même temps de réponse qu'un compte existant
            throw UnauthorizedException("INVALID_CREDENTIALS", "Adresse ou mot de passe incorrect")
        }
        val (agent, hash, failures) = row
        val (attempts, lockedUntil) = failures
        val now = now()
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw BusinessException("ACCOUNT_LOCKED", "Compte bloqué après trop d'erreurs, réessayez plus tard", HttpStatus.LOCKED)
        }
        if (!encoder.matches(password, hash)) {
            val next = attempts + 1
            val lock = if (next >= props.maxFailedAttempts) now.plusMinutes(props.lockMinutes) else null
            jdbc.sql("UPDATE backoffice_agent SET failed_attempts = :a, locked_until = :l WHERE id = :id")
                .param("a", if (lock != null) 0 else next).param("l", lock).param("id", agent.id).update()
            if (lock != null) throw BusinessException("ACCOUNT_LOCKED", "Compte bloqué après trop d'erreurs, réessayez plus tard", HttpStatus.LOCKED)
            throw UnauthorizedException("INVALID_CREDENTIALS", "Adresse ou mot de passe incorrect")
        }
        if (!agent.active) throw ForbiddenException("AGENT_DISABLED", "Compte agent désactivé")
        jdbc.sql("UPDATE backoffice_agent SET failed_attempts = 0, locked_until = NULL WHERE id = :id").param("id", agent.id).update()

        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        jdbc.sql(
            """INSERT INTO agent_session (token_hash, agent_id, created_at, last_seen_at, expires_at)
               VALUES (:h, :a, :now, :now, :exp)""",
        ).param("h", sha256(token)).param("a", agent.id).param("now", now).param("exp", now.plusHours(props.sessionMaxHours)).update()
        audit.record(ActorType.AGENT, agent.id, "AGENT_LOGIN", "AGENT", agent.id)
        return AgentTokens(token, props.sessionIdleMinutes * 60, agent)
    }

    @Transactional
    fun authenticate(token: String): AgentPrincipal {
        val hash = sha256(token)
        val now = now()
        val principal = jdbc.sql(
            """SELECT a.id, a.role FROM agent_session s JOIN backoffice_agent a ON a.id = s.agent_id
               WHERE s.token_hash = :h AND s.revoked_at IS NULL AND s.expires_at > :now
                 AND s.last_seen_at > :idle AND a.active""",
        ).param("h", hash).param("now", now).param("idle", now.minusMinutes(props.sessionIdleMinutes))
            .query { rs, _ -> AgentPrincipal(rs.getObject("id", UUID::class.java), AgentRole.valueOf(rs.getString("role"))) }
            .list().firstOrNull() ?: throw UnauthorizedException("SESSION_INVALID", "Session agent invalide ou expirée")
        jdbc.sql("UPDATE agent_session SET last_seen_at = :now WHERE token_hash = :h").param("now", now).param("h", hash).update()
        return principal
    }

    @Transactional
    fun logout(token: String) {
        jdbc.sql("UPDATE agent_session SET revoked_at = :now WHERE token_hash = :h AND revoked_at IS NULL")
            .param("now", now()).param("h", sha256(token)).update()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val DUMMY_HASH = BCryptPasswordEncoder(12).encode("pas-un-vrai-mot-de-passe")
    }
}
