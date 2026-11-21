package cd.capitalinvest.platform.cards

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.NotFoundException
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

data class PinRequest(val pin: String)

data class OrderPhysicalCardRequest(@field:Size(max = 80) val pickupBranch: String, val pin: String)

data class ReplaceCardRequest(@field:Size(max = 80) val pickupBranch: String? = null, val pin: String)

data class BlockCardRequest(val reason: CardBlockReason, val pin: String)

data class ActivateCardRequest(@field:Size(max = 12) val activationCode: String, @field:Size(max = 4) val cardPin: String)

data class CardControlsRequest(val dailyLimitMinor: Long, val onlinePayments: Boolean)

/**
 * Cartes du client. Le PIN de l'app est demandé pour ce qui coûte de l'argent, rend une carte utilisable
 * ou montre son numéro ; il est vérifié dans sa propre transaction, pour qu'un mauvais PIN reste compté.
 * Geler ne le demande pas : le client qui ne trouve plus sa carte doit pouvoir le faire en une touche.
 */
@RestController
@RequestMapping("/v1/me/cards")
class CardController(
    private val cards: CardService,
    private val auth: AuthService,
) {
    @GetMapping
    fun overview(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = cards.overview(customerId)

    @PostMapping("/virtual")
    fun createVirtual(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): ResponseEntity<Card> =
        ResponseEntity.status(HttpStatus.CREATED).body(cards.createVirtual(customerId))

    @PostMapping("/physical")
    fun orderPhysical(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: OrderPhysicalCardRequest,
    ): ResponseEntity<Card> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.status(HttpStatus.CREATED).body(cards.orderPhysical(customerId, key, body.pickupBranch))
    }

    @GetMapping("/{id}")
    fun get(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = cards.get(customerId, id)

    @PostMapping("/{id}/freeze")
    fun freeze(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = cards.freeze(customerId, id)

    @PostMapping("/{id}/unfreeze")
    fun unfreeze(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @RequestBody body: PinRequest): Card {
        auth.confirmWithPin(customerId, body.pin)
        return cards.unfreeze(customerId, id)
    }

    @PostMapping("/{id}/block")
    fun block(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @RequestBody body: BlockCardRequest): Card {
        auth.confirmWithPin(customerId, body.pin)
        return cards.block(customerId, id, body.reason)
    }

    @PostMapping("/{id}/delete")
    fun delete(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @RequestBody body: PinRequest): ResponseEntity<Void> {
        auth.confirmWithPin(customerId, body.pin)
        cards.delete(customerId, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/replace")
    fun replace(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: ReplaceCardRequest,
    ): ResponseEntity<Card> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.status(HttpStatus.CREATED).body(cards.replace(customerId, id, key, body.pickupBranch))
    }

    @PostMapping("/{id}/activate")
    fun activate(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @Valid @RequestBody body: ActivateCardRequest) =
        cards.activate(customerId, id, body.activationCode, body.cardPin)

    @PutMapping("/{id}/controls")
    fun controls(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @RequestBody body: CardControlsRequest) =
        cards.setControls(customerId, id, body.dailyLimitMinor, body.onlinePayments)

    /** Numéro complet : jamais mis en cache, ni par le téléphone ni par un intermédiaire. */
    @PostMapping("/{id}/details")
    fun details(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID, @RequestBody body: PinRequest): ResponseEntity<CardDetails> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cards.details(customerId, id))
    }

    @GetMapping("/{id}/authorizations")
    fun authorizations(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = cards.authorizations(customerId, id, limit)
}

data class SimulatedCardPayment(
    val amountMinor: Long,
    val merchantName: String,
    val merchantCity: String? = null,
    val online: Boolean = false,
    val reference: String? = null,
    val currency: String = "USD",
)

data class SimulatedProduction(val status: CardStatus)

/**
 * Remplace le processeur de cartes en développement : fabrication et livraison en agence, code imprimé
 * sur le pli, paiements chez un commerçant. Désactivé en production.
 */
@RestController
@RequestMapping("/v1/simulators/cards")
@ConditionalOnProperty("capital-invest.simulators-enabled", havingValue = "true")
class CardSimulatorController(
    private val cards: CardService,
    private val authorizations: CardAuthorizationService,
    private val simulator: SimulatedCardProcessor,
) {
    private fun simulated(id: UUID): CardService.Row {
        val card = cards.row(id) ?: throw NotFoundException("CARD_NOT_FOUND", "Carte introuvable")
        if (card.processor != SimulatedCardProcessor.NAME) throw NotFoundException("NOT_SIMULATED", "Cette carte n'est pas chez le simulateur")
        return card
    }

    /** Étape suivante de la fabrication ; sans corps : commandée → en fabrication → prête au retrait. */
    @PostMapping("/{id}/production")
    fun production(@PathVariable id: UUID, @RequestBody(required = false) body: SimulatedProduction?): Card {
        val card = simulated(id)
        val next = body?.status ?: when (card.status) {
            CardStatus.ORDERED -> CardStatus.IN_PRODUCTION
            else -> CardStatus.READY_FOR_PICKUP
        }
        return cards.productionAdvanced(id, next)
    }

    @GetMapping("/{id}/activation-code")
    fun activationCode(@PathVariable id: UUID): Map<String, String> = mapOf("activationCode" to simulator.activationCode(simulated(id).token))

    @PostMapping("/{id}/authorizations")
    fun pay(@PathVariable id: UUID, @RequestBody body: SimulatedCardPayment): CardAuthorization {
        simulated(id)
        return authorizations.authorize(
            id,
            AuthorizationRequest(
                processorReference = body.reference ?: "SIM-${UUID.randomUUID().toString().take(12)}",
                amountMinor = body.amountMinor, currency = body.currency, merchantName = body.merchantName,
                merchantCity = body.merchantCity, online = body.online,
            ),
        )
    }

    @PostMapping("/authorizations/{authorizationId}/reversal")
    fun reverse(@PathVariable authorizationId: UUID): CardAuthorization = authorizations.reverse(authorizationId)
}
