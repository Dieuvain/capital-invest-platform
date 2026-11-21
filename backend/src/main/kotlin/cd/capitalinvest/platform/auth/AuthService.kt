package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.notifications.NotificationService
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.customers.CustomerService
import cd.capitalinvest.platform.customers.CustomerView
import cd.capitalinvest.platform.customers.Language
import cd.capitalinvest.platform.payments.PendingTransferService
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

/** Une connexion du client, pour « Connexions récentes ». */
data class SessionView(
    val id: UUID,
    val deviceName: String?,
    val createdAt: OffsetDateTime,
    val lastSeenAt: OffsetDateTime,
    /** La session de ce téléphone. */
    val current: Boolean,
    /** Encore utilisable : ni fermée, ni expirée. */
    val active: Boolean,
)

data class SessionTokens(val accessToken: String, val expiresInSeconds: Long, val customer: CustomerView)

/** Clé de connexion par empreinte, remise une seule fois au téléphone qui la garde derrière sa biométrie. */
data class BiometricKey(val key: String)

class UnauthorizedException(code: String, message: String) : BusinessException(code, message, HttpStatus.UNAUTHORIZED)

@Service
class AuthService(
    private val jdbc: JdbcClient,
    private val otp: OtpService,
    private val customers: CustomerRepository,
    private val customerService: CustomerService,
    private val props: SecurityProperties,
    private val clock: Clock,
    private val notifications: NotificationService,
    private val audit: AuditLog,
    private val pendingTransfers: PendingTransferService,
) {
    private val encoder = BCryptPasswordEncoder(12)
    private val random = SecureRandom()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Inscription : code SMS valide, puis ouverture du compte au KYC 1 avec PIN et première session. */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun register(phone: String, code: String, fullName: String, language: Language, pin: String, deviceId: String, deviceName: String? = null): SessionTokens {
        PinPolicy.validate(pin)
        otp.verify(phone, OtpPurpose.REGISTER, code)
        val view = customerService.open(phone, fullName, language)
        customers.setPinHash(view.customer.id, encoder.encode(pin))
        // Un proche lui a peut-être envoyé de l'argent avant qu'il ait un compte.
        pendingTransfers.claimFor(view.customer)
        return openSession(view, deviceId, deviceName)
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun login(phone: String, pin: String, deviceId: String, deviceName: String? = null): SessionTokens {
        val customer = customers.findByPhone(phone)
            ?: throw UnauthorizedException("INVALID_CREDENTIALS", "Numéro ou PIN incorrect")
        checkPin(customer.id, pin)
        requireActive(customer)
        val known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM session WHERE customer_id = :c AND device_id = :d)")
            .param("c", customer.id).param("d", deviceId.take(100)).query(Boolean::class.java).single()
        if (!known) notifications.newDevice(customer.id, deviceName?.trim(), now())
        return openSession(customerService.get(customer.id), deviceId, deviceName)
    }

    /** Demande de code SMS pour un PIN oublié. Ne révèle jamais si le numéro a un compte. */
    @Transactional
    fun requestPinReset(phone: String) {
        if (customers.findByPhone(phone) != null) otp.request(phone, OtpPurpose.RESET_PIN)
    }

    /**
     * PIN oublié : code SMS valide, nouveau PIN, toutes les anciennes sessions fermées.
     * Les envois d'argent restent ensuite suspendus pendant le délai de sécurité (vol de SIM).
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun resetPin(phone: String, code: String, newPin: String, deviceId: String, deviceName: String? = null): SessionTokens {
        PinPolicy.validate(newPin)
        val customer = customers.findByPhone(phone) ?: throw BusinessException("OTP_INVALID", "Code invalide ou expiré")
        otp.verify(phone, OtpPurpose.RESET_PIN, code)
        requireActive(customer)
        val now = now()
        customers.setPinHash(customer.id, encoder.encode(newPin))
        jdbc.sql("UPDATE customer SET pin_reset_at = :now WHERE id = :id").param("now", now).param("id", customer.id).update()
        jdbc.sql("UPDATE session SET revoked_at = :now WHERE customer_id = :id AND revoked_at IS NULL")
            .param("now", now).param("id", customer.id).update()
        // Vol de SIM possible : l'empreinte d'aucun téléphone ne doit suffire, il faudra le nouveau PIN partout.
        revokeBiometricKeys(customer.id, now)
        notifications.pinChanged(customer.id, now)
        return openSession(customerService.get(customer.id), deviceId, deviceName)
    }

    /**
     * Téléphone perdu ou volé : le client bloque son compte depuis un autre téléphone, avec son numéro et son PIN.
     * Le PIN est exigé pour qu'un inconnu ne puisse pas bloquer le compte de quelqu'un d'autre ; les erreurs comptent
     * comme à la connexion. Déjà bloqué : ne change rien.
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun blockWithPhone(phone: String, pin: String) {
        val customer = customers.findByPhone(phone)
            ?: throw UnauthorizedException("INVALID_CREDENTIALS", "Numéro ou PIN incorrect")
        checkPin(customer.id, pin)
        block(customer)
    }

    /** Blocage depuis l'app, PIN redemandé : toutes les sessions tombent, y compris celle-ci. */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun blockFromApp(customerId: UUID, pin: String) {
        checkPin(customerId, pin)
        block(customers.findById(customerId) ?: throw UnauthorizedException("SESSION_INVALID", "Session invalide"))
    }

    private fun block(customer: Customer) {
        // Un compte suspendu par la conformité ou fermé le reste : le client n'y change rien.
        if (customer.status != "ACTIVE") return
        val now = now()
        jdbc.sql("UPDATE customer SET status = 'SUSPENDED', blocked_by_customer_at = :now WHERE id = :id AND status = 'ACTIVE'")
            .param("now", now).param("id", customer.id).update()
        jdbc.sql("UPDATE session SET revoked_at = :now WHERE customer_id = :id AND revoked_at IS NULL")
            .param("now", now).param("id", customer.id).update()
        revokeBiometricKeys(customer.id, now)
        audit.record(ActorType.CUSTOMER, customer.id, "CUSTOMER_SELF_BLOCKED", "CUSTOMER", customer.id)
        notifications.accountBlocked(customer.id, now)
    }

    private fun requireActive(customer: Customer) {
        if (customer.status == "ACTIVE") return
        if (customer.blockedByCustomerAt != null) {
            throw BusinessException(
                "CUSTOMER_BLOCKED", "Compte bloqué à votre demande. Pour le débloquer, présentez-vous en agence avec votre pièce d'identité.",
                HttpStatus.FORBIDDEN,
            )
        }
        if (customer.status == "CLOSED") {
            throw BusinessException("CUSTOMER_CLOSED", "Ce compte est fermé. Pour en ouvrir un nouveau sur ce numéro, présentez-vous en agence.", HttpStatus.FORBIDDEN)
        }
        throw BusinessException("CUSTOMER_NOT_ACTIVE", "Compte suspendu ou fermé", HttpStatus.FORBIDDEN)
    }

    /** Confirmation d'une opération sensible (envoi, retrait, blocage de carte). */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun confirmWithPin(customerId: UUID, pin: String) = checkPin(customerId, pin)

    /** Renvoie le client d'un jeton valide et prolonge la session (expiration après 15 min d'inactivité). */
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

    /**
     * Changement de PIN par le client connecté, qui connaît l'ancien : les autres sessions sont fermées,
     * celle-ci reste ouverte. Pas de délai de sécurité sur les envois, contrairement au PIN oublié :
     * l'ancien PIN prouve que c'est bien le titulaire.
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun changePin(customerId: UUID, token: String, currentPin: String, newPin: String) {
        checkPin(customerId, currentPin)
        PinPolicy.validate(newPin)
        if (currentPin == newPin) throw BusinessException("PIN_SAME", "Choisissez un PIN différent de l'actuel")
        val now = now()
        customers.setPinHash(customerId, encoder.encode(newPin))
        revokeOthers(customerId, token, now)
        notifications.pinChanged(customerId, now)
    }

    /** Les 10 dernières connexions du client, la session en cours en tête. */
    @Transactional(readOnly = true)
    fun sessions(customerId: UUID, token: String): List<SessionView> {
        val current = sha256(token)
        val now = now()
        return jdbc.sql(
            """SELECT id, token_hash, device_name, created_at, last_seen_at, expires_at, revoked_at FROM session
               WHERE customer_id = :c ORDER BY (token_hash = :h) DESC, created_at DESC LIMIT 10""",
        ).param("c", customerId).param("h", current).query { rs, _ ->
            val lastSeen = rs.getObject("last_seen_at", OffsetDateTime::class.java)
            val isCurrent = rs.getString("token_hash") == current
            SessionView(
                id = rs.getObject("id", UUID::class.java),
                deviceName = rs.getString("device_name"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                lastSeenAt = lastSeen,
                current = isCurrent,
                active = isCurrent || (
                    rs.getObject("revoked_at") == null &&
                        now.isBefore(rs.getObject("expires_at", OffsetDateTime::class.java)) &&
                        now.isBefore(lastSeen.plusMinutes(props.sessionIdleMinutes))
                    ),
            )
        }.list()
    }

    /** Ferme toutes les sessions du client sauf celle d'où vient la demande. Renvoie le nombre fermé. */
    @Transactional
    fun signOutOthers(customerId: UUID, token: String): Int = revokeOthers(customerId, token, now())

    /** Ferme les autres sessions, et retire l'empreinte des autres téléphones : ils devront repasser par le PIN. */
    private fun revokeOthers(customerId: UUID, token: String, now: OffsetDateTime): Int {
        jdbc.sql(
            """UPDATE biometric_key SET revoked_at = :now WHERE customer_id = :c AND revoked_at IS NULL
               AND device_id <> (SELECT device_id FROM session WHERE token_hash = :h)""",
        ).param("now", now).param("c", customerId).param("h", sha256(token)).update()
        return jdbc.sql("UPDATE session SET revoked_at = :now WHERE customer_id = :c AND token_hash <> :h AND revoked_at IS NULL")
            .param("now", now).param("c", customerId).param("h", sha256(token)).update()
    }

    // ---- Connexion par empreinte -----------------------------------------------------------------

    /**
     * Active l'empreinte sur le téléphone de cette session. Le PIN est redemandé : un téléphone laissé ouvert ne
     * suffit pas à l'activer. Une clé déjà active sur ce téléphone est remplacée.
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun enableBiometric(customerId: UUID, token: String, pin: String): BiometricKey {
        checkPin(customerId, pin)
        val deviceId = sessionDevice(token)
        val now = now()
        jdbc.sql("UPDATE biometric_key SET revoked_at = :now WHERE customer_id = :c AND device_id = :d AND revoked_at IS NULL")
            .param("now", now).param("c", customerId).param("d", deviceId).update()
        val key = randomSecret()
        jdbc.sql(
            """INSERT INTO biometric_key (id, customer_id, device_id, key_hash, created_at)
               VALUES (:id, :c, :d, :h, :now)""",
        ).param("id", UUID.randomUUID()).param("c", customerId).param("d", deviceId).param("h", sha256(key)).param("now", now).update()
        audit.record(ActorType.CUSTOMER, customerId, "BIOMETRIC_ENABLED", "CUSTOMER", customerId)
        return BiometricKey(key)
    }

    /** Retire l'empreinte du téléphone de cette session. Sans clé active : ne change rien. */
    @Transactional
    fun disableBiometric(customerId: UUID, token: String) {
        jdbc.sql("UPDATE biometric_key SET revoked_at = :now WHERE customer_id = :c AND device_id = :d AND revoked_at IS NULL")
            .param("now", now()).param("c", customerId).param("d", sessionDevice(token)).update()
    }

    /**
     * Connexion par empreinte : le téléphone a vérifié le doigt du client, puis présente la clé reçue à l'activation.
     * La clé ne vaut que pour ce numéro et ce téléphone. Clé inconnue ou retirée : BIOMETRIC_INVALID, l'app oublie
     * sa clé et demande le PIN.
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun biometricLogin(phone: String, deviceId: String, key: String, deviceName: String? = null): SessionTokens {
        val invalid = UnauthorizedException("BIOMETRIC_INVALID", "Empreinte désactivée sur ce téléphone, entrez votre PIN")
        val customer = customers.findByPhone(phone) ?: throw invalid
        val id = jdbc.sql(
            """SELECT id FROM biometric_key WHERE customer_id = :c AND device_id = :d AND key_hash = :h AND revoked_at IS NULL""",
        ).param("c", customer.id).param("d", deviceId.take(100)).param("h", sha256(key)).query(UUID::class.java).optional().orElse(null)
            ?: throw invalid
        requireActive(customer)
        jdbc.sql("UPDATE biometric_key SET last_used_at = :now WHERE id = :id").param("now", now()).param("id", id).update()
        return openSession(customerService.get(customer.id), deviceId, deviceName)
    }

    private fun revokeBiometricKeys(customerId: UUID, now: OffsetDateTime) {
        jdbc.sql("UPDATE biometric_key SET revoked_at = :now WHERE customer_id = :c AND revoked_at IS NULL")
            .param("now", now).param("c", customerId).update()
    }

    private fun sessionDevice(token: String): String =
        jdbc.sql("SELECT device_id FROM session WHERE token_hash = :h").param("h", sha256(token)).query(String::class.java).optional()
            .orElseThrow { UnauthorizedException("SESSION_INVALID", "Session invalide") }

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

    private fun randomSecret(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun openSession(view: CustomerView, deviceId: String, deviceName: String?): SessionTokens {
        val token = randomSecret()
        val now = now()
        jdbc.sql(
            """INSERT INTO session (token_hash, customer_id, device_id, device_name, created_at, last_seen_at, expires_at)
               VALUES (:h, :c, :d, :name, :now, :now, :exp)""",
        )
            .param("h", sha256(token)).param("c", view.customer.id).param("d", deviceId.take(100))
            .param("name", deviceName?.trim()?.take(80)?.ifBlank { null })
            .param("now", now).param("exp", now.plusHours(props.sessionMaxHours))
            .update()
        return SessionTokens(token, props.sessionIdleMinutes * 60, view)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
