package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.ledger.PostingResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TransferService(
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
) {
    /** Envoi d'argent entre deux clients Capital Invest, identifiés par le numéro du destinataire. */
    @Transactional
    fun transfer(idempotencyKey: String, fromCustomerId: UUID, toPhone: String, amountMinor: Long, note: String?): PostingResult {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val sender = customers.findById(fromCustomerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val recipient = customers.findByPhone(toPhone)
            ?: throw NotFoundException("RECIPIENT_NOT_FOUND", "Aucun client Capital Invest avec ce numéro")
        if (sender.id == recipient.id) throw BusinessException("SAME_ACCOUNT", "Impossible d'envoyer à soi-même")
        if (sender.status != "ACTIVE" || recipient.status != "ACTIVE") {
            throw BusinessException("CUSTOMER_NOT_ACTIVE", "Un des deux clients est suspendu ou fermé")
        }
        val from = ledgerRepository.findCustomerWallet(sender.id, Currency.USD)!!
        val to = ledgerRepository.findCustomerWallet(recipient.id, Currency.USD)!!
        return ledger.post(
            PostingRequest(
                idempotencyKey = "transfer:${sender.id}:$idempotencyKey",
                type = "TRANSFER",
                description = note?.take(140),
                lines = listOf(
                    PostingLine(from.id, Direction.DEBIT, amountMinor),
                    PostingLine(to.id, Direction.CREDIT, amountMinor),
                ),
            ),
        )
    }
}
