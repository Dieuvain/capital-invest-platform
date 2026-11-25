package cd.capitalinvest.platform.credit

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreditQuoteRequest(val amountMinor: Long, val durationMonths: Int)

data class CreditApplyRequest(val amountMinor: Long, val durationMonths: Int, val pin: String)

data class CreditRepayRequest(val mode: RepaymentMode = RepaymentMode.DUE, val pin: String)

/**
 * Crédit express du client. Accepter l'offre et rembourser demandent le PIN (analyse UML : un crédit inclut
 * toujours la confirmation par PIN) ; le PIN est vérifié dans sa propre transaction, pour qu'un mauvais PIN reste compté.
 */
@RestController
@RequestMapping("/v1/me/credit")
class CreditController(private val credit: CreditService, private val auth: AuthService) {
    @GetMapping
    fun overview(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = credit.overview(customerId)

    @PostMapping("/quote")
    fun quote(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestBody body: CreditQuoteRequest) =
        credit.quote(customerId, body.amountMinor, body.durationMonths)

    @PostMapping
    fun apply(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CreditApplyRequest,
    ): ResponseEntity<CreditView> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.status(HttpStatus.CREATED).body(credit.apply(customerId, key, body.amountMinor, body.durationMonths))
    }

    @GetMapping("/{id}")
    fun get(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = credit.get(customerId, id)

    @PostMapping("/{id}/cancel")
    fun cancel(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = credit.cancel(customerId, id)

    @PostMapping("/{id}/repayments")
    fun repay(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CreditRepayRequest,
    ): CreditView {
        auth.confirmWithPin(customerId, body.pin)
        return credit.repay(customerId, id, key, body.mode)
    }
}

data class CreditDecisionRequest(@field:Size(max = 500) val note: String? = null)

data class CreditRescheduleRequest(val durationMonths: Int, @field:Size(max = 500) val note: String? = null)

/**
 * Crédits dans le back-office. Les agents de crédit et les administrateurs examinent, approuvent,
 * refusent et rééchelonnent ; le passage en perte et les réglages sont réservés aux administrateurs.
 */
@RestController
@RequestMapping("/v1/backoffice")
class BackofficeCreditController(private val credit: CreditService) {
    private fun requireCredit(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.CREDIT_OFFICER) {
            throw ForbiddenException("CREDIT_ONLY", "Réservé aux agents de crédit et aux administrateurs")
        }
    }

    private fun requireAdmin(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN) throw ForbiddenException("ADMIN_ONLY", "Réservé aux administrateurs (comité de crédit)")
    }

    @GetMapping("/credits")
    fun list(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestParam(required = false) status: CreditStatus?): List<BackofficeCredit> {
        requireCredit(me)
        return credit.list(status)
    }

    @GetMapping("/credits/{id}")
    fun get(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): BackofficeCredit {
        requireCredit(me)
        return credit.backofficeGet(id)
    }

    @GetMapping("/customers/{id}/credits")
    fun forCustomer(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): List<BackofficeCredit> {
        requireCredit(me)
        return credit.forCustomer(id)
    }

    @PostMapping("/credits/{id}/approve")
    fun approve(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody body: CreditDecisionRequest): BackofficeCredit {
        requireCredit(me)
        return credit.approve(me.id, id, body.note)
    }

    @PostMapping("/credits/{id}/reject")
    fun reject(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody body: CreditDecisionRequest): BackofficeCredit {
        requireCredit(me)
        return credit.reject(me.id, id, body.note)
    }

    @PostMapping("/credits/{id}/reschedule")
    fun reschedule(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody body: CreditRescheduleRequest): BackofficeCredit {
        requireCredit(me)
        return credit.reschedule(me.id, id, body.durationMonths, body.note)
    }

    @PostMapping("/credits/{id}/write-off")
    fun writeOff(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody body: CreditDecisionRequest): BackofficeCredit {
        requireAdmin(me)
        return credit.writeOff(me.id, id, body.note)
    }

    @GetMapping("/credit-settings")
    fun settings(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): CreditSettings {
        requireCredit(me)
        return credit.settings()
    }

    @PutMapping("/credit-settings")
    fun updateSettings(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestBody body: CreditSettings): CreditSettings {
        requireAdmin(me)
        return credit.updateSettings(me.id, body)
    }
}

@ConfigurationProperties("capital-invest.credit")
data class CreditProperties(
    /** Prélèvements et rappels : la tâche tourne toutes les heures ; désactivée dans les tests, qui l'appellent eux-mêmes. */
    val pollerEnabled: Boolean = true,
)

@Component
class CreditJobs(private val credit: CreditService, private val props: CreditProperties) {
    @Scheduled(fixedDelayString = "\${capital-invest.credit.poll-delay-ms:3600000}", initialDelay = 60_000)
    fun run() {
        if (props.pollerEnabled) credit.runDue()
    }
}

/** Pour essayer l'app sans attendre un mois : lancer tout de suite les prélèvements du jour. Désactivé en production. */
@RestController
@RequestMapping("/v1/simulators/credit")
@ConditionalOnProperty("capital-invest.simulators-enabled", havingValue = "true")
class CreditSimulatorController(private val credit: CreditService) {
    @PostMapping("/collections")
    fun collect(): Map<String, Int> = mapOf("collected" to credit.runDue())
}
