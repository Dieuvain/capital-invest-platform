package cd.capitalinvest.platform.ledger

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.customers.CustomerService
import cd.capitalinvest.platform.customers.CustomerView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class BalanceResponse(val accountId: UUID, val currency: Currency, val balanceMinor: Long)

/** Tout ce que le client connecté peut lire sur lui-même. Il ne voit jamais le compte d'un autre. */
@RestController
@RequestMapping("/v1/me")
class MeController(
    private val customers: CustomerService,
    private val ledger: LedgerService,
    private val repository: LedgerRepository,
) {
    @GetMapping
    fun me(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): CustomerView = customers.get(customerId)

    @GetMapping("/balance")
    fun balance(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): BalanceResponse {
        val wallet = customers.get(customerId).walletAccountId
        return BalanceResponse(wallet, Currency.USD, ledger.balance(wallet))
    }

    @GetMapping("/entries")
    fun entries(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<LedgerEntry> = repository.entriesForAccount(customers.get(customerId).walletAccountId, limit.coerceIn(1, 200))
}
