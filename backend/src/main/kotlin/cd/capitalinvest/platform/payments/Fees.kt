package cd.capitalinvest.platform.payments

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

/**
 * Frais d'envoi entre clients : un pourcentage borné par un minimum et un maximum.
 * Valeurs PROVISOIRES, à fixer par la direction générale (grille tarifaire).
 */
@ConfigurationProperties("capital-invest.fees")
data class FeeProperties(
    /** En points de base : 100 = 1 %. */
    val transferBps: Long = 100,
    val transferMinMinor: Long = 10,
    val transferMaxMinor: Long = 500,
)

data class FeeQuote(val amountMinor: Long, val feeMinor: Long, val totalMinor: Long)

@Service
class FeeService(private val props: FeeProperties) {
    /** Arrondi au centime le plus proche (demi vers le haut), puis bornes min et max. */
    fun transferFee(amountMinor: Long): Long {
        require(amountMinor > 0)
        val raw = (amountMinor * props.transferBps + 5_000) / 10_000
        return raw.coerceIn(props.transferMinMinor, props.transferMaxMinor)
    }

    fun quote(amountMinor: Long): FeeQuote {
        val fee = transferFee(amountMinor)
        return FeeQuote(amountMinor, fee, amountMinor + fee)
    }
}
