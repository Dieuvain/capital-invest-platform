package cd.capitalinvest.platform.ledger

import cd.capitalinvest.platform.common.Currency
import java.time.OffsetDateTime
import java.util.UUID

enum class Direction { DEBIT, CREDIT }

enum class AccountType { CUSTOMER_WALLET, OPERATOR_SETTLEMENT, FEES_REVENUE, PAYOUT_IN_TRANSIT, MERCHANT_WALLET, CUSTOMER_SAVINGS, PENDING_TRANSFERS }

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
