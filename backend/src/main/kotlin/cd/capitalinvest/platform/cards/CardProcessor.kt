package cd.capitalinvest.platform.cards

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Réseau de la carte, choisi avec la banque sponsor. */
enum class CardNetwork { VISA, MASTERCARD }

/** Ce que Capital Invest envoie au processeur pour émettre une carte. */
data class CardIssueRequest(
    /** Identifiant de la carte chez Capital Invest, que le processeur garde comme référence. */
    val cardId: UUID,
    val customerId: UUID,
    val embossedName: String,
    val physical: Boolean,
    val dailyLimitMinor: Long,
    val onlinePayments: Boolean,
    /** Carte physique : agence où le processeur la livre. */
    val pickupBranch: String?,
)

/** Ce que le processeur renvoie : jamais le numéro complet. */
data class IssuedCard(val token: String, val network: CardNetwork, val last4: String, val expiryMonth: Int, val expiryYear: Int)

/** Numéro et cryptogramme, affichés au client et jamais stockés chez Capital Invest. */
data class SensitiveCardDetails(val pan: String, val cvv: String)

/** État de la carte tel que le processeur l'applique aux autorisations. */
enum class ProcessorCardState { ACTIVE, SUSPENDED, TERMINATED }

class CardProcessorException(message: String) : RuntimeException(message)

/**
 * Processeur de cartes (émission, statut, contrôles, affichage sécurisé, fabrication des cartes physiques).
 * Aucun partenaire n'est encore choisi : le simulateur tient sa place, et le vrai processeur
 * n'aura qu'à implémenter cette interface et être nommé dans `capital-invest.cards.processor`.
 *
 * Les autorisations font le chemin inverse : le processeur appelle Capital Invest, qui décide
 * (voir [CardAuthorizationService]).
 */
interface CardProcessor {
    /** Nom enregistré avec chaque carte, pour savoir à quel processeur s'adresser ensuite. */
    val name: String

    fun issue(request: CardIssueRequest): IssuedCard

    /** Gel (SUSPENDED), dégel (ACTIVE), blocage définitif ou suppression (TERMINATED). */
    fun setState(token: String, state: ProcessorCardState)

    fun setControls(token: String, dailyLimitMinor: Long, onlinePayments: Boolean)

    /**
     * Activation d'une carte physique remise au client : le code imprimé sur le pli et le PIN qu'il choisit.
     * Le PIN part directement au processeur ; Capital Invest ne le garde pas. Faux si le code ne correspond pas.
     */
    fun activate(token: String, activationCode: String, cardPin: String): Boolean

    /** Numéro complet à afficher dans l'app. Un vrai processeur passe par son kit mobile certifié PCI DSS. */
    fun reveal(token: String): SensitiveCardDetails
}

/** Choisit le processeur configuré ; le simulateur est refusé quand les simulateurs sont coupés (production). */
@Component
class CardProcessorRouter(
    processors: List<CardProcessor>,
    private val props: CardsProperties,
    @Value("\${capital-invest.simulators-enabled:false}") private val simulatorsEnabled: Boolean,
) {
    private val byName = processors.associateBy { it.name }

    init {
        require(props.processor in byName) { "Processeur de cartes inconnu : ${props.processor}" }
        require(props.processor != SimulatedCardProcessor.NAME || simulatorsEnabled) {
            "Le processeur de cartes simulé ne peut pas servir quand les simulateurs sont désactivés"
        }
    }

    /** Processeur des nouvelles cartes. */
    fun current(): CardProcessor = byName.getValue(props.processor)

    /** Processeur d'une carte existante. */
    fun byName(name: String): CardProcessor = byName[name] ?: throw CardProcessorException("Processeur $name absent")
}

/**
 * Processeur simulé, pour le développement et les tests. Il ne garde rien en mémoire : numéro,
 * cryptogramme et code d'activation se déduisent du jeton, et restent donc les mêmes après un redémarrage.
 * Numéros dans la plage de test Visa (4000 00…), valides au contrôle de Luhn.
 */
@Component
class SimulatedCardProcessor(
    @Value("\${capital-invest.cards.simulator-secret:simulateur-cartes}") secret: String,
) : CardProcessor {
    companion object {
        const val NAME = "simulator"

        /** Chiffre de contrôle de Luhn d'un numéro sans son dernier chiffre. */
        fun luhnDigit(partial: String): Int {
            val sum = partial.reversed().mapIndexed { i, c ->
                val d = c - '0'
                if (i % 2 == 0) (d * 2).let { if (it > 9) it - 9 else it } else d
            }.sum()
            return (10 - sum % 10) % 10
        }

        fun luhnValid(pan: String): Boolean = pan.length >= 12 && luhnDigit(pan.dropLast(1)) == pan.last() - '0'
    }

    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "HmacSHA256")

    override val name = NAME

    /** Tokens rejetés par [setState] TERMINATED : simule un refus quand on touche à une carte résiliée. */
    private val terminated = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun digits(token: String, purpose: String, count: Int): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal("$purpose:$token".toByteArray())
        return mac.joinToString("") { ((it.toInt() and 0xff) % 10).toString() }.take(count)
    }

    fun pan(token: String): String {
        val partial = "400000" + digits(token, "pan", 9)
        return partial + luhnDigit(partial)
    }

    /** Code imprimé sur le pli de la carte physique ; le simulateur de l'API le donne aux tests. */
    fun activationCode(token: String): String = digits(token, "activation", 6)

    override fun issue(request: CardIssueRequest): IssuedCard {
        val token = "sim_card_" + UUID.randomUUID().toString().replace("-", "")
        val years = if (request.physical) 4 else 3
        val expiry = java.time.YearMonth.now(java.time.ZoneOffset.UTC).plusYears(years.toLong())
        return IssuedCard(token, CardNetwork.VISA, pan(token).takeLast(4), expiry.monthValue, expiry.year)
    }

    override fun setState(token: String, state: ProcessorCardState) {
        if (token in terminated) throw CardProcessorException("Carte résiliée chez le processeur")
        if (state == ProcessorCardState.TERMINATED) terminated += token
    }

    override fun setControls(token: String, dailyLimitMinor: Long, onlinePayments: Boolean) {
        if (token in terminated) throw CardProcessorException("Carte résiliée chez le processeur")
    }

    override fun activate(token: String, activationCode: String, cardPin: String): Boolean =
        MessageDigest.isEqual(activationCode.toByteArray(), activationCode(token).toByteArray())

    override fun reveal(token: String): SensitiveCardDetails {
        if (token in terminated) throw CardProcessorException("Carte résiliée chez le processeur")
        return SensitiveCardDetails(pan(token), digits(token, "cvv", 3))
    }
}
