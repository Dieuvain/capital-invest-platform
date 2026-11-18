package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.customers.CustomerService
import cd.capitalinvest.platform.customers.CustomerView
import cd.capitalinvest.platform.customers.Language
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

data class SessionTokens(val accessToken: String, val expiresInSeconds: Long, val customer: CustomerView)

class UnauthorizedException(code: String, message: String) : BusinessException(code, message, HttpStatus.UNAUTHORIZED)

@Service
class AuthService(
    private val jdbc: JdbcClient,
    private val otp: OtpService,
    private val customers: CustomerRepository,
    private val customerService: CustomerService,
    private val props: SecurityProperties,
    private val clock: Clock,
) {
    private val encoder = BCryptPasswordEncoder(12)
    private val random = SecureRandom()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Inscription : code SMS valide, puis ouverture du compte au KYC 1 avec PIN et première session. */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun register(phone: String, code: String, fullName: String, language: Language, pin: String, deviceId: String): SessionTokens {
        PinPolicy.validate(pin)
        otp.verify(phone, OtpPurpose.REGISTER, code)
        val view = customerService.open(phone, fullName, language)
        customers.setPinHash(view.customer.id, encoder.encode(pin))
        return openSession(view, deviceId)
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun login(phone: String, pin: String, deviceId: String): SessionTokens {
        val customer = customers.findByPhone(phone)
            ?: throw UnauthorizedException("INVALID_CREDENTIALS", "Numéro ou PIN incorrect")
        checkPin(customer.id, pin)
        if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Compte suspendu ou fermé", HttpStatus.FORBIDDEN)
        return openSession(customerService.get(customer.id), deviceId)
    }

    /** Confirmation d'une opération sensible (envoi, retrait, blocage de carte). */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun confirmWithPin(customerId: UUID, pin: String) = checkPin(customerId, pin)

    /** Renvoie le client d'un jeton valide et prolonge la session (expiration après 30 min d'inactivité). */
    @Transactional
    fun authenticate(token: String): UUID {
        val hash = sha256(token)
        val row = jdbc.sql("SELECT customer_id, last_seen_at, expires_at, revoked_at FROM session WHERE token_hash = :h")
            .param("h", hash)
            .query { rs, _ ->
                listOf(
                    rs.getObject("customer_id", UUID::class.java),
                    rs.getObject("last_seen_at", OffsetDateTime::class.java),
                    rs.getObject("expires_at", OffsetDateTime::class.java),
                    rs.getObject("revoked_at", OffsetDateTime::class.java),
                )
            }.optional().orElse(null) ?: throw UnauthorizedException("SESSION_INVALID", "Session invalide")
        val customerId = row[0] as UUID
        val lastSeen = row[1] as OffsetDateTime
        val expiresAt = row[2] as OffsetDateTime
        val now = now()
        if (row[3] != null || now.isAfter(expiresAt) || now.isAfter(lastSeen.plusMinutes(props.sessionIdleMinutes))) {
            throw UnauthorizedException("SESSION_EXPIRED", "Session expirée, entrez votre PIN")
        }
        jdbc.sql("UPDATE session SET last_seen_at = :now WHERE token_hash = :h").param("now", now).param("h", hash).update()
        return customerId
    }

    @Transactional
    fun logout(token: String) {
        jdbc.sql("UPDATE session SET revoked_at = :now WHERE token_hash = :h AND revoked_at IS NULL")
            .param("now", now()).param("h", sha256(token)).update()
    }

    private fun checkPin(customerId: UUID, pin: String) {
        val state = customers.lockPinState(customerId) ?: throw UnauthorizedException("INVALID_CREDENTIALS", "Numéro ou PIN incorrect")
        val now = now()
        if (state.lockedUntil != null && now.isBefore(state.lockedUntil)) {
            throw BusinessException("PIN_LOCKED", "PIN bloqué après trop d'erreurs, réessayez plus tard", HttpStatus.LOCKED)
        }
        if (state.hash == null || !encoder.matches(pin, state.hash)) {
            val attempts = state.failedAttempts + 1
            if (attempts >= props.pinMaxAttempts) {
                customers.updatePinFailures(customerId, 0, now.plusMinutes(props.pinLockMinutes))
                throw BusinessException("PIN_LOCKED", "PIN bloqué après trop d'erreurs, réessayez plus tard", HttpStatus.LOCKED)
            }
            customers.updatePinFailures(customerId, attempts, null)
            throw UnauthorizedException("INVALID_CREDENTIALS", "Numéro ou PIN incorrect")
        }
        if (state.failedAttempts > 0 || state.lockedUntil != null) customers.updatePinFailures(customerId, 0, null)
    }

    private fun openSession(view: CustomerView, deviceId: String): SessionTokens {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = now()
        jdbc.sql(
            """INSERT INTO session (token_hash, customer_id, device_id, created_at, last_seen_at, expires_at)
               VALUES (:h, :c, :d, :now, :now, :exp)""",
        )
            .param("h", sha256(token)).param("c", view.customer.id).param("d", deviceId.take(100))
            .param("now", now).param("exp", now.plusHours(props.sessionMaxHours))
            .update()
        return SessionTokens(token, props.sessionIdleMinutes * 60, view)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
