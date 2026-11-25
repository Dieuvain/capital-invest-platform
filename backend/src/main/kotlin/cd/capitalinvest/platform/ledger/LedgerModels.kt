package cd.capitalinvest.platform.ledger

import cd.capitalinvest.platform.common.Currency
import java.time.OffsetDateTime
import java.util.UUID

enum class Direction { DEBIT, CREDIT }

enum class AccountType { CUSTOMER_WALLET, OPERATOR_SETTLEMENT, FEES_REVENUE, PAYOUT_IN_TRANSIT, MERCHANT_WALLET, CUSTOMER_SAVINGS, PENDING_TRANSFERS, CUSTOMER_POT, FX_POSITION, LOANS_RECEIVABLE, INTEREST_REVENUE, LOAN_LOSSES }

enum class AccountStatus { ACTIVE, FROZEN, CLOSED }

data class LedgerAccount(
    val id: UUID,
    val code: String?,
    val type: AccountType,
    val currency: Currency,
    val normalSide: Direction,
    val allowNegative: Boolean,
    val customerId: UUID?,
    val status: AccountStatus,
)

/** Une ligne d'une demande d'écriture. */
data class PostingLine(val accountId: UUID, val direction: Direction, val amountMinor: Long)

data class PostingRequest(
    val idempotencyKey: String,
    val type: String,
    val description: String?,
    val lines: List<PostingLine>,
    /**
     * Change entre devises (USD et CDF) : les comptes peuvent être de devises différentes,
     * et les débits doivent égaler les crédits dans chaque devise. Faux pour tout le reste.
     */
    val crossCurrency: Boolean = false,
)

data class LedgerEntry(
    val id: UUID,
    val transactionId: UUID,
    val accountId: UUID,
    val direction: Direction,
    val amountMinor: Long,
    val currency: Currency,
    val createdAt: OffsetDateTime,
)

data class LedgerTransaction(
    val id: UUID,
    val idempotencyKey: String,
    val type: String,
    val description: String?,
    val createdAt: OffsetDateTime,
    val entries: List<LedgerEntry>,
)
