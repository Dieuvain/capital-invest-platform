package cd.capitalinvest.platform.limits

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.notifications.NotificationService
import cd.capitalinvest.platform.notifications.NotificationService.Companion.money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** [dailyOutMinor] null : revenir au plafond du niveau KYC. */
data class DailyLimitRequest(val dailyOutMinor: Long?, val pin: String)

/**
 * Plafond d'envoi journalier choisi par le client, sous celui de son niveau. Baisser est immédiat ;
 * remonter attend [LimitsProperties.personalLimitRaiseDelayHours] heures et prévient le client par SMS,
 * pour qu'un voleur qui connaît le PIN ne puisse pas lever la protection et vider le compte d'un coup.
 */
@Service
class PersonalLimitService(
    private val jdbc: JdbcClient,
    private val limits: LimitsService,
    private val props: LimitsProperties,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val auth: AuthService,
    private val notifications: NotificationService,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    @Transactional(noRollbackFor = [BusinessException::class])
    fun set(customerId: UUID, requested: Long?, pin: String): LimitsView {
        auth.confirmWithPin(customerId, pin)
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val level = limits.tier(customer).dailyOutMinor
        if (requested != null && (requested < props.personalLimitMinMinor || requested > level)) {
            throw BusinessException(
                "INVALID_DAILY_LIMIT",
                "Choisissez entre ${money(props.personalLimitMinMinor)} et ${money(level)}, le plafond de votre niveau",
            )
        }
        // Au plafond du niveau, il n'y a plus de plafond personnel.
        val value = requested?.takeIf { it < level }
        val current = limits.personalLimit(customerId).dailyOutMinor
        val now = now()
        if ((value ?: level) <= (current ?: level)) {
            jdbc.sql("UPDATE customer SET daily_limit_minor = :v, pending_daily_limit_minor = NULL, pending_daily_limit_at = NULL WHERE id = :id")
                .param("v", value).param("id", customerId).update()
            audit.record(ActorType.CUSTOMER, customerId, "DAILY_LIMIT_LOWERED", "CUSTOMER", customerId, value?.let(::money) ?: "plafond du niveau")
        } else {
            val at = now.plusHours(props.personalLimitRaiseDelayHours)
            // La valeur en vigueur est réécrite : une hausse précédente arrivée à échéance devient définitive.
            jdbc.sql(
                """UPDATE customer SET daily_limit_minor = :cur, pending_daily_limit_minor = :v, pending_daily_limit_at = :at WHERE id = :id""",
            ).param("cur", current).param("v", value).param("at", at).param("id", customerId).update()
            audit.record(ActorType.CUSTOMER, customerId, "DAILY_LIMIT_RAISE_REQUESTED", "CUSTOMER", customerId, value?.let(::money) ?: "plafond du niveau")
            notifications.dailyLimitRaisePending(customerId, value ?: level, props.personalLimitRaiseDelayHours, now)
        }
        return view(customerId)
    }

    /** Annuler une hausse en attente : sans PIN, puisque cela ne fait que garder la protection. */
    @Transactional
    fun cancelPending(customerId: UUID): LimitsView {
        val current = limits.personalLimit(customerId)
        if (current.pending != null) {
            jdbc.sql("UPDATE customer SET pending_daily_limit_minor = NULL, pending_daily_limit_at = NULL WHERE id = :id")
                .param("id", customerId).update()
            audit.record(ActorType.CUSTOMER, customerId, "DAILY_LIMIT_RAISE_CANCELLED", "CUSTOMER", customerId)
        }
        return view(customerId)
    }

    private fun view(customerId: UUID): LimitsView {
        val customer = customers.findById(customerId)!!
        val wallet = ledger.findCustomerWallet(customerId, Currency.USD) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        return limits.view(customer, wallet.id)
    }
}

@RestController
@RequestMapping("/v1/me/limits/daily")
class PersonalLimitController(private val service: PersonalLimitService) {

    @PutMapping
    fun set(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestBody b: DailyLimitRequest): LimitsView =
        service.set(customerId, b.dailyOutMinor, b.pin)

    @DeleteMapping("/pending")
    fun cancelPending(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): LimitsView = service.cancelPending(customerId)
}
