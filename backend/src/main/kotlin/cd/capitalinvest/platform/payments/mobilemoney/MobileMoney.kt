package cd.capitalinvest.platform.payments.mobilemoney

import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.payments.Operator
import cd.capitalinvest.platform.payments.Topup
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Branchement des opérateurs mobile money.
 *
 * Chaque opérateur est routé vers un prestataire (`routes`) : l'agrégateur pawaPay pour commencer,
 * un contrat direct plus tard, ou le simulateur en développement. Changer de prestataire pour un
 * opérateur ne touche qu'à cette configuration et au connecteur concerné.
 */
@ConfigurationProperties("capital-invest.mobile-money")
data class MobileMoneyProperties(
    /** Opérateur → nom du connecteur (SIMULATOR, PAWAPAY…). Un opérateur absent passe par le simulateur. */
    val routes: Map<Operator, String> = emptyMap(),
    /** Première vérification chez le prestataire, puis l'intervalle double jusqu'au maximum. */
    val firstCheckSeconds: Long = 10,
    val maxCheckIntervalSeconds: Long = 300,
    /** Inconnue du prestataire après ce délai : la demande n'est jamais arrivée, la recharge échoue. */
    val notFoundTimeoutMinutes: Long = 30,
    /** Toujours sans réponse définitive après ce délai : un agent doit vérifier. */
    val reviewAfterHours: Long = 24,
    val pollerEnabled: Boolean = true,
    val reconciliationEnabled: Boolean = true,
    val pawapay: PawapayProperties = PawapayProperties(),
)

data class PawapayProperties(
    val baseUrl: String = "https://api.sandbox.pawapay.io",
    val apiToken: String = "",
    /** Refuser toute notification non signée ou mal signée. A laisser à true en production. */
    val requireSignedCallbacks: Boolean = true,
    /** Codes des opérateurs chez pawaPay, pour la RDC. */
    val providers: Map<Operator, String> = mapOf(
        Operator.MPESA to "VODACOM_MPESA_COD",
        Operator.AIRTEL to "AIRTEL_COD",
        Operator.ORANGE to "ORANGE_COD",
    ),
    /** Domaine public de l'API tel que pawaPay l'appelle (derrière un proxy, l'en-tête Host peut différer). */
    val callbackAuthority: String? = null,
    val timeoutSeconds: Long = 20,
)

data class PaymentRequest(
    val topupId: UUID,
    val operator: Operator,
    /** Numéro au format international sans « + » : 243970000001. */
    val msisdn: String,
    val amountMinor: Long,
    val currency: Currency,
)

/** Réponse à la demande de paiement. */
sealed interface InitiateResult {
    /** Le prestataire a pris la demande ; le client va recevoir l'invite sur son téléphone. */
    data class Accepted(val reference: String?) : InitiateResult

    /** Refus net : rien ne sera débité chez le client. */
    data class Rejected(val code: String, val message: String) : InitiateResult

    /** Réponse absente ou illisible : on ne sait pas, il faut relire le statut plus tard. */
    data class Unknown(val reason: String) : InitiateResult
}

/** Statut lu chez le prestataire. Seul Completed permet de créditer. */
sealed interface CheckResult {
    data object Pending : CheckResult

    data class Completed(val amountMinor: Long, val currency: String, val reference: String?) : CheckResult

    data class Failed(val code: String, val message: String) : CheckResult

    /** Le prestataire ne connaît pas cette recharge. */
    data object NotFound : CheckResult

    /** Erreur réseau ou réponse inattendue : on ne conclut rien. */
    data class Uncertain(val reason: String) : CheckResult
}

/** Notification reçue d'un prestataire, avant toute interprétation. */
class IncomingNotification(
    val method: String,
    val authority: String,
    val path: String,
    /** En-têtes, noms en minuscules. */
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** Ce qu'on retient d'une notification : quelle recharge relire, et si la signature est bonne (null : non signée). */
data class ParsedNotification(val topupId: UUID?, val signatureValid: Boolean?)

interface MobileMoneyConnector {
    /** Nom stocké sur chaque recharge, pour toujours la relire chez le même prestataire. */
    val name: String

    /** Compte de règlement où l'argent de cet opérateur arrive chez ce prestataire. */
    fun settlementCode(operator: Operator): String

    fun initiate(request: PaymentRequest): InitiateResult

    fun check(topup: Topup): CheckResult

    fun parseNotification(notification: IncomingNotification): ParsedNotification

    /** Une notification sans signature valable est refusée. */
    val requiresSignature: Boolean get() = false
}

class UnknownProviderException(name: String) : RuntimeException("Prestataire mobile money inconnu : $name")

@Component
class MobileMoneyRouter(
    connectors: List<MobileMoneyConnector>,
    private val properties: MobileMoneyProperties,
    @Value("\${capital-invest.simulators-enabled:false}") private val simulatorsEnabled: Boolean,
) {
    private val byName = connectors.associateBy { it.name }

    init {
        Operator.entries.forEach { op ->
            val name = route(op)
            require(name in byName) { "capital-invest.mobile-money.routes.$op : prestataire inconnu « $name »" }
            require(name != SimulatorConnector.NAME || simulatorsEnabled) {
                "$op passe par le simulateur alors que les simulateurs sont désactivés : configurez capital-invest.mobile-money.routes.$op"
            }
        }
        if (Operator.entries.any { route(it) == PawapayConnector.NAME }) {
            require(properties.pawapay.apiToken.isNotBlank()) { "PAWAPAY_API_TOKEN est obligatoire quand un opérateur passe par pawaPay" }
        }
    }

    private fun route(op: Operator) = properties.routes[op]?.uppercase() ?: SimulatorConnector.NAME

    fun forOperator(op: Operator): MobileMoneyConnector = byName.getValue(route(op))

    fun byName(name: String): MobileMoneyConnector = byName[name.uppercase()] ?: throw UnknownProviderException(name)
}
