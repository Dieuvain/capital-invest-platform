package cd.capitalinvest.platform.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("capital-invest.security")
data class SecurityProperties(
    val otpSecret: String,
    val otpTtlMinutes: Long = 5,
    val otpMaxAttempts: Int = 5,
    val otpMaxPer10Minutes: Int = 3,
    val pinMaxAttempts: Int = 5,
    val pinLockMinutes: Long = 30,
    val sessionIdleMinutes: Long = 30,
    val sessionMaxHours: Long = 12,
)
