package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.ledger.LedgerTransaction
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class TopupRequest(val operator: Operator, @field:Positive val amountMinor: Long)

data class TransferRequest(
    val toPhone: String,
    @field:Positive val amountMinor: Long,
    @field:Size(max = 140) val note: String? = null,
    /** Chaque envoi se confirme par le PIN, même avec une session ouverte. */
    val pin: String,
)

data class TransferResponse(
    val transaction: LedgerTransaction,
    val replayed: Boolean,
    val amountMinor: Long,
    val feeMinor: Long,
    val totalDebitedMinor: Long,
)

@RestController
@RequestMapping("/v1")
class PaymentsController(
    private val topups: TopupService,
    private val transfers: TransferService,
    private val auth: AuthService,
    private val fees: FeeService,
) {

    @PostMapping("/topups")
    fun initiateTopup(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: TopupRequest,
    ): ResponseEntity<Topup> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(topups.initiate(key, customerId, body.operator, body.amountMinor))

    @GetMapping("/topups/{id}")
    fun getTopup(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID): Topup {
        val topup = topups.get(id)
        // Une recharge d'un autre client répond comme si elle n'existait pas.
        if (topup.customerId != customerId) throw NotFoundException("TOPUP_NOT_FOUND", "Recharge introuvable")
        return topup
    }

    @PostMapping("/transfers")
    fun transfer(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: TransferRequest,
    ): ResponseEntity<TransferResponse> {
        // Vérification du PIN dans sa propre transaction : un échec reste compté même si l'envoi n'a pas lieu.
        auth.confirmWithPin(customerId, body.pin)
        val result = transfers.transfer(key, customerId, body.toPhone, body.amountMinor, body.note)
        val status = if (result.posting.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(
            TransferResponse(
                result.posting.transaction, result.posting.replayed,
                result.amountMinor, result.feeMinor, result.amountMinor + result.feeMinor,
            ),
        )
    }

    /** Montant des frais avant confirmation, pour l'écran « Vérifier l'envoi ». */
    @GetMapping("/transfers/fee")
    fun transferFee(@RequestParam amountMinor: Long): FeeQuote {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        return fees.quote(amountMinor)
    }
}

data class SimulatedCallback(val topupId: UUID, val success: Boolean, val operatorReference: String? = null)

/** Remplace le webhook de l'opérateur en développement. Désactivé en production. */
@RestController
@RequestMapping("/v1/simulators/mobile-money")
@ConditionalOnProperty("capital-invest.simulators-enabled", havingValue = "true")
class MobileMoneySimulatorController(private val topups: TopupService) {

    @PostMapping("/callbacks")
    fun callback(@RequestBody body: SimulatedCallback): Topup =
        topups.applyOperatorResult(body.topupId, body.success, body.operatorReference ?: "SIM-${UUID.randomUUID().toString().take(8)}")
}
