package cd.capitalinvest.platform.payments.mobilemoney

import cd.capitalinvest.platform.payments.Operator
import cd.capitalinvest.platform.payments.Topup
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Prestataire simulé, pour le développement et les tests. Il suit exactement le même chemin qu'un vrai :
 * l'endpoint de simulation fixe le résultat « chez l'opérateur », puis la recharge relit ce statut
 * comme elle le ferait chez pawaPay.
 */
@Component
class SimulatorConnector(private val json: ObjectMapper) : MobileMoneyConnector {
    companion object {
        const val NAME = "SIMULATOR"
    }

    private val outcomes = ConcurrentHashMap<UUID, CheckResult>()
    private val payoutOutcomes = ConcurrentHashMap<UUID, CheckResult>()

    override val name = NAME

    override fun settlementCode(operator: Operator) = operator.settlementAccountCode

    override fun initiate(request: PaymentRequest) = InitiateResult.Accepted(null)

    override fun check(topup: Topup): CheckResult = outcomes[topup.id] ?: CheckResult.Pending

    override fun initiatePayout(request: PaymentRequest) = InitiateResult.Accepted(null)

    override fun checkPayout(withdrawalId: UUID): CheckResult = payoutOutcomes[withdrawalId] ?: CheckResult.Pending

    override fun parseNotification(notification: IncomingNotification): ParsedNotification {
        val body = runCatching { json.readTree(notification.body) }.getOrNull()
        fun id(field: String) = runCatching { UUID.fromString(body!![field].asText()) }.getOrNull()
        return ParsedNotification(id("topupId"), null, id("withdrawalId"))
    }

    /** Ce que « l'opérateur » répondra désormais pour ce retrait. */
    fun setPayoutOutcome(withdrawalId: UUID, outcome: CheckResult) {
        payoutOutcomes[withdrawalId] = outcome
    }

    /** Ce que « l'opérateur » répondra désormais pour cette recharge. */
    fun setOutcome(topupId: UUID, outcome: CheckResult) {
        outcomes[topupId] = outcome
    }
}
