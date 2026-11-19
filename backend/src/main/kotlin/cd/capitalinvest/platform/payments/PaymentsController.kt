package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.ledger.LedgerTransaction
import cd.capitalinvest.platform.payments.mobilemoney.CheckResult
import cd.capitalinvest.platform.payments.mobilemoney.SimulatorConnector
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

data class WithdrawalRequest(
    val operator: Operator,
    @field:Positive val amountMinor: Long,
    /** Chaque retrait se confirme par le PIN, comme un envoi. */
    val pin: String,
)

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
    private val withdrawals: WithdrawalService,
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

    /** Retrait vers le compte mobile money du client (son propre numéro). L'argent est bloqué dès maintenant. */
    @PostMapping("/withdrawals")
    fun withdraw(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: WithdrawalRequest,
    ): ResponseEntity<Withdrawal> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(withdrawals.initiate(key, customerId, body.operator, body.amountMinor))
    }

    @GetMapping("/withdrawals/fee")
    fun withdrawalFee(@RequestParam amountMinor: Long): FeeQuote {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        return fees.withdrawalQuote(amountMinor)
    }

    @GetMapping("/withdrawals/{id}")
    fun getWithdrawal(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID): Withdrawal {
        val w = withdrawals.get(id)
        if (w.customerId != customerId) throw NotFoundException("WITHDRAWAL_NOT_FOUND", "Retrait introuvable")
        return w
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

data class SimulatedCallback(
    val topupId: UUID,
    val success: Boolean,
    val operatorReference: String? = null,
    /** Montant réellement encaissé par « l'opérateur », pour simuler un écart. Par défaut, celui demandé. */
    val amountMinor: Long? = null,
)

data class SimulatedPayout(
    val withdrawalId: UUID,
    val success: Boolean,
    val amountMinor: Long? = null,
    val failureCode: String? = null,
)

/**
 * Remplace l'opérateur en développement : fixe ce que « l'opérateur » répondra, puis déclenche la même
 * relecture du statut qu'une vraie notification. Désactivé en production.
 */
@RestController
@RequestMapping("/v1/simulators/mobile-money")
@ConditionalOnProperty("capital-invest.simulators-enabled", havingValue = "true")
class MobileMoneySimulatorController(
    private val topups: TopupService,
    private val withdrawals: WithdrawalService,
    private val simulator: SimulatorConnector,
) {

    /** Versement simulé : « l'opérateur » paie (ou refuse) le retrait, puis le statut est relu. */
    @PostMapping("/payouts")
    fun payout(@RequestBody body: SimulatedPayout): Withdrawal {
        val w = withdrawals.get(body.withdrawalId)
        if (w.provider != SimulatorConnector.NAME) {
            throw ConflictException("NOT_SIMULATED", "Ce retrait passe par ${w.provider}, pas par le simulateur")
        }
        simulator.setPayoutOutcome(
            w.id,
            if (body.success) {
                CheckResult.Completed(body.amountMinor ?: w.amountMinor, w.currency.name, "SIM-${UUID.randomUUID().toString().take(8)}")
            } else {
                CheckResult.Failed(body.failureCode ?: "SIMULATED_FAILURE", "Versement refusé par l'opérateur (simulation)")
            },
        )
        return withdrawals.refresh(w.id)
    }


    @PostMapping("/callbacks")
    fun callback(@RequestBody body: SimulatedCallback): Topup {
        val topup = topups.get(body.topupId)
        if (topup.provider != SimulatorConnector.NAME) {
            throw ConflictException("NOT_SIMULATED", "Cette recharge passe par ${topup.provider}, pas par le simulateur")
        }
        simulator.setOutcome(
            topup.id,
            if (body.success) {
                CheckResult.Completed(
                    body.amountMinor ?: topup.amountMinor, topup.currency.name,
                    body.operatorReference ?: "SIM-${UUID.randomUUID().toString().take(8)}",
                )
            } else {
                CheckResult.Failed("SIMULATED_FAILURE", "Paiement refusé par l'opérateur (simulation)")
            },
        )
        return topups.refresh(topup.id)
    }
}
