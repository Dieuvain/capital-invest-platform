package cd.capitalinvest.platform.payments

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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class TopupRequest(val customerId: UUID, val operator: Operator, @field:Positive val amountMinor: Long)

// customerId est temporaire : il viendra du jeton d'authentification au sprint suivant.
data class TransferRequest(
    val fromCustomerId: UUID,
    val toPhone: String,
    @field:Positive val amountMinor: Long,
    @field:Size(max = 140) val note: String? = null,
)

data class TransferResponse(val transaction: LedgerTransaction, val replayed: Boolean)

@RestController
@RequestMapping("/v1")
class PaymentsController(private val topups: TopupService, private val transfers: TransferService) {

    @PostMapping("/topups")
    fun initiateTopup(
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: TopupRequest,
    ): ResponseEntity<Topup> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(topups.initiate(key, body.customerId, body.operator, body.amountMinor))

    @GetMapping("/topups/{id}")
    fun getTopup(@PathVariable id: UUID): Topup = topups.get(id)

    @PostMapping("/transfers")
    fun transfer(
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: TransferRequest,
    ): ResponseEntity<TransferResponse> {
        val result = transfers.transfer(key, body.fromCustomerId, body.toPhone, body.amountMinor, body.note)
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(TransferResponse(result.transaction, result.replayed))
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
