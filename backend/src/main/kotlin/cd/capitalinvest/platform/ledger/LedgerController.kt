package cd.capitalinvest.platform.ledger

import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class BalanceResponse(val accountId: UUID, val currency: Currency, val balanceMinor: Long)

@RestController
@RequestMapping("/v1/accounts")
class LedgerController(private val service: LedgerService, private val repository: LedgerRepository) {

    @GetMapping("/{id}/balance")
    fun balance(@PathVariable id: UUID): BalanceResponse {
        val account = repository.findAccount(id) ?: throw NotFoundException("ACCOUNT_NOT_FOUND", "Compte introuvable : $id")
        return BalanceResponse(id, account.currency, service.balance(id))
    }

    @GetMapping("/{id}/entries")
    fun entries(@PathVariable id: UUID, @RequestParam(defaultValue = "50") limit: Int): List<LedgerEntry> {
        repository.findAccount(id) ?: throw NotFoundException("ACCOUNT_NOT_FOUND", "Compte introuvable : $id")
        return repository.entriesForAccount(id, limit.coerceIn(1, 200))
    }
}
