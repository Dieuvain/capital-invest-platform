package cd.capitalinvest.platform.ledger

import cd.capitalinvest.platform.common.Currency
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class LedgerRepository(private val jdbc: JdbcClient) {

    fun createAccount(account: LedgerAccount) {
        jdbc.sql(
            """INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative, customer_id, status)
               VALUES (:id, :code, :type, :currency, :normalSide, :allowNegative, :customerId, :status)""",
        )
            .param("id", account.id)
            .param("code", account.code)
            .param("type", account.type.name)
            .param("currency", account.currency.name)
            .param("normalSide", account.normalSide.name)
            .param("allowNegative", account.allowNegative)
            .param("customerId", account.customerId)
            .param("status", account.status.name)
            .update()
    }

    fun findAccount(id: UUID): LedgerAccount? =
        jdbc.sql("SELECT * FROM ledger_account WHERE id = :id").param("id", id).query(::mapAccount).optional().orElse(null)

    fun findAccountByCode(code: String): LedgerAccount? =
        jdbc.sql("SELECT * FROM ledger_account WHERE code = :code").param("code", code).query(::mapAccount).optional().orElse(null)

    fun findCustomerWallet(customerId: UUID, currency: Currency): LedgerAccount? =
        jdbc.sql("SELECT * FROM ledger_account WHERE customer_id = :c AND type = 'CUSTOMER_WALLET' AND currency = :cur")
            .param("c", customerId)
            .param("cur", currency.name)
            .query(::mapAccount)
            .optional()
            .orElse(null)

    /** Verrouille les comptes dans un ordre fixe (par id) pour éviter les interblocages. */
    fun lockAccounts(ids: Collection<UUID>): List<LedgerAccount> =
        jdbc.sql("SELECT * FROM ledger_account WHERE id IN (:ids) ORDER BY id FOR UPDATE")
            .param("ids", ids)
            .query(::mapAccount)
            .list()

    fun balance(accountId: UUID): Long =
        jdbc.sql(
            """SELECT COALESCE(SUM(CASE WHEN e.direction = a.normal_side THEN e.amount_minor ELSE -e.amount_minor END), 0)
               FROM ledger_account a LEFT JOIN ledger_entry e ON e.account_id = a.id
               WHERE a.id = :id""",
        ).param("id", accountId).query(Long::class.java).single()

    /** Insère l'en-tête ; renvoie false si la clé d'idempotence existe déjà. */
    fun insertTransaction(id: UUID, key: String, hash: String, type: String, description: String?): Boolean =
        jdbc.sql(
            """INSERT INTO ledger_transaction (id, idempotency_key, request_hash, type, description)
               VALUES (:id, :key, :hash, :type, :description)
               ON CONFLICT (idempotency_key) DO NOTHING""",
        )
            .param("id", id)
            .param("key", key)
            .param("hash", hash)
            .param("type", type)
            .param("description", description)
            .update() == 1

    fun findTransactionHeaderByKey(key: String): Pair<UUID, String>? =
        jdbc.sql("SELECT id, request_hash FROM ledger_transaction WHERE idempotency_key = :key")
            .param("key", key)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("request_hash") }
            .optional()
            .orElse(null)

    fun insertEntry(transactionId: UUID, line: PostingLine, currency: Currency) {
        jdbc.sql(
            """INSERT INTO ledger_entry (id, transaction_id, account_id, direction, amount_minor, currency)
               VALUES (:id, :tx, :account, :direction, :amount, :currency)""",
        )
            .param("id", UUID.randomUUID())
            .param("tx", transactionId)
            .param("account", line.accountId)
            .param("direction", line.direction.name)
            .param("amount", line.amountMinor)
            .param("currency", currency.name)
            .update()
    }

    fun findTransaction(id: UUID): LedgerTransaction? {
        val header = jdbc.sql("SELECT * FROM ledger_transaction WHERE id = :id").param("id", id)
            .query { rs, _ ->
                LedgerTransaction(
                    id = rs.getObject("id", UUID::class.java),
                    idempotencyKey = rs.getString("idempotency_key"),
                    type = rs.getString("type"),
                    description = rs.getString("description"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    entries = emptyList(),
                )
            }.optional().orElse(null) ?: return null
        val entries = jdbc.sql("SELECT * FROM ledger_entry WHERE transaction_id = :id ORDER BY direction DESC, id")
            .param("id", id).query(::mapEntry).list()
        return header.copy(entries = entries)
    }

    fun entriesForAccount(accountId: UUID, limit: Int): List<LedgerEntry> =
        jdbc.sql("SELECT * FROM ledger_entry WHERE account_id = :id ORDER BY created_at DESC, id LIMIT :limit")
            .param("id", accountId)
            .param("limit", limit)
            .query(::mapEntry)
            .list()

    private fun mapAccount(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = LedgerAccount(
        id = rs.getObject("id", UUID::class.java),
        code = rs.getString("code"),
        type = AccountType.valueOf(rs.getString("type")),
        currency = Currency.valueOf(rs.getString("currency")),
        normalSide = Direction.valueOf(rs.getString("normal_side")),
        allowNegative = rs.getBoolean("allow_negative"),
        customerId = rs.getObject("customer_id", UUID::class.java),
        status = AccountStatus.valueOf(rs.getString("status")),
    )

    private fun mapEntry(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = LedgerEntry(
        id = rs.getObject("id", UUID::class.java),
        transactionId = rs.getObject("transaction_id", UUID::class.java),
        accountId = rs.getObject("account_id", UUID::class.java),
        direction = Direction.valueOf(rs.getString("direction")),
        amountMinor = rs.getLong("amount_minor"),
        currency = Currency.valueOf(rs.getString("currency")),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )
}
