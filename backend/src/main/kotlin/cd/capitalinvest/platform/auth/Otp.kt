package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.common.BusinessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class OtpPurpose { REGISTER, RESET_PIN }

@Service
class OtpService(
    private val jdbc: JdbcClient,
    private val sms: SmsGateway,
    private val props: SecurityProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    @Transactional
    fun request(phone: String, purpose: OtpPurpose) {
        val recent = jdbc.sql("SELECT count(*) FROM otp_challenge WHERE phone = :p AND created_at > :since")
            .param("p", phone).param("since", now().minusMinutes(10))
            .query(Long::class.java).single()
        if (recent >= props.otpMaxPer10Minutes) {
            throw BusinessException("OTP_RATE_LIMITED", "Trop de codes demandés, réessayez dans 10 minutes", HttpStatus.TOO_MANY_REQUESTS)
        }
        val code = "%06d".format(random.nextInt(1_000_000))
        jdbc.sql(
            """INSERT INTO otp_challenge (id, phone, purpose, code_hash, expires_at, created_at)
               VALUES (:id, :p, :purpose, :hash, :exp, :now)""",
        )
            .param("id", UUID.randomUUID()).param("p", phone).param("purpose", purpose.name)
            .param("hash", hmac(phone, code)).param("exp", now().plusMinutes(props.otpTtlMinutes)).param("now", now())
            .update()
        sms.send(phone, "Capital Invest : votre code est $code. Ne le communiquez a personne, meme a un agent.")
    }

    /** Vérifie et consomme le dernier code valide. Les tentatives ratées restent comptées. */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun verify(phone: String, purpose: OtpPurpose, code: String) {
        val challenge = jdbc.sql(
            """SELECT id, code_hash, attempts FROM otp_challenge
               WHERE phone = :p AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now
               ORDER BY created_at DESC LIMIT 1 FOR UPDATE""",
        )
            .param("p", phone).param("purpose", purpose.name).param("now", now())
            .query { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getString("code_hash"), rs.getInt("attempts")) }
            .optional().orElse(null)
            ?: throw BusinessException("OTP_INVALID", "Code invalide ou expiré")
        val (id, hash, attempts) = challenge
        if (attempts >= props.otpMaxAttempts) throw BusinessException("OTP_INVALID", "Code invalide ou expiré")

        if (!MessageDigest.isEqual(hash.toByteArray(), hmac(phone, code).toByteArray())) {
            jdbc.sql("UPDATE otp_challenge SET attempts = attempts + 1 WHERE id = :id").param("id", id).update()
            throw BusinessException("OTP_INVALID", "Code invalide ou expiré")
        }
        jdbc.sql("UPDATE otp_challenge SET consumed_at = :now WHERE id = :id").param("now", now()).param("id", id).update()
    }

    private fun hmac(phone: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(props.otpSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$phone:$code".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
