package cd.capitalinvest.platform.savings

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

data class SavingsGoal(val label: String, val targetMinor: Long, val remainingMinor: Long, val progressPercent: Int)

enum class SavingsMoveKind { DEPOSIT, WITHDRAWAL }

data class SavingsMove(val id: UUID, val kind: SavingsMoveKind, val amountMinor: Long, val createdAt: OffsetDateTime)

data class SavingsView(
    val balanceMinor: Long,
    /** Ce qui reste sur le portefeuille, pour proposer « tout mettre de côté ». */
    val walletBalanceMinor: Long,
    val currency: Currency,
    /** Mis de côté moins repris depuis le 1er du mois, heure de Lubumbashi. */
    val savedThisMonthMinor: Long,
    val goal: SavingsGoal?,
    val moves: List<SavingsMove>,
)

/**
 * Épargne du client : un compte du grand livre à côté de son portefeuille.
 * L'argent ne quitte pas Capital Invest, il change seulement de compte : pas de frais, pas de PIN,
 * et les plafonds d'envoi ne s'appliquent pas. Le solde maximal du niveau KYC compte les deux comptes ensemble.
 */
@Service
class SavingsService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private val zone get() = ZoneId.of(limits.timezone)

    private fun customer(id: UUID): Customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    private fun wallet(customerId: UUID) = ledgerRepository.findCustomerWallet(customerId, Currency.USD)
        ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    fun savingsAccountId(customerId: UUID): UUID? =
        jdbc.sql("SELECT id FROM ledger_account WHERE customer_id = :c AND type = 'CUSTOMER_SAVINGS' AND currency = 'USD'")
            .param("c", customerId).query(UUID::class.java).list().firstOrNull()

    /** Ouvert au premier dépôt. L'index unique fait qu'un second dépôt simultané retrouve le même compte. */
    private fun openSavings(customerId: UUID): UUID {
        savingsAccountId(customerId)?.let { return it }
        // Sens normal au crédit : une dette de Capital Invest envers le client, comme le portefeuille.
        jdbc.sql(
            """INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative, customer_id, status)
               VALUES (:id, NULL, 'CUSTOMER_SAVINGS', 'USD', 'CREDIT', FALSE, :c, 'ACTIVE')
               ON CONFLICT (customer_id, currency) WHERE type = 'CUSTOMER_SAVINGS' DO NOTHING""",
        ).param("id", UUID.randomUUID()).param("c", customerId).update()
        return savingsAccountId(customerId)!!
    }

    @Transactional(readOnly = true)
    fun view(customerId: UUID): SavingsView {
        customer(customerId)
        val walletId = wallet(customerId).id
        val savingsId = savingsAccountId(customerId)
        val balance = savingsId?.let(ledgerRepository::balance) ?: 0
        val moves = savingsId?.let(::moves).orEmpty()
        val monthStart = clock.instant().atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toOffsetDateTime()
        val thisMonth = savingsId?.let {
            jdbc.sql(
                """SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                   FROM ledger_entry WHERE account_id = :a AND created_at >= :since""",
            ).param("a", it).param("since", monthStart).query(Long::class.java).single()
        } ?: 0
        val goal = jdbc.sql("SELECT label, target_minor FROM savings_goal WHERE customer_id = :c").param("c", customerId)
            .query { rs, _ ->
                val target = rs.getLong("target_minor")
                SavingsGoal(
                    label = rs.getString("label"),
                    targetMinor = target,
                    remainingMinor = (target - balance).coerceAtLeast(0),
                    progressPercent = (balance * 100 / target).coerceIn(0, 100).toInt(),
                )
            }.list().firstOrNull()
        return SavingsView(balance, ledgerRepository.balance(walletId), Currency.USD, thisMonth, goal, moves)
    }

    private fun moves(savingsId: UUID): List<SavingsMove> =
        jdbc.sql(
            """SELECT t.id, e.direction, e.amount_minor, t.created_at FROM ledger_entry e
               JOIN ledger_transaction t ON t.id = e.transaction_id
               WHERE e.account_id = :a ORDER BY t.created_at DESC, t.id DESC LIMIT 20""",
        ).param("a", savingsId).query { rs, _ ->
            SavingsMove(
                id = rs.getObject("id", UUID::class.java),
                kind = if (rs.getString("direction") == "CREDIT") SavingsMoveKind.DEPOSIT else SavingsMoveKind.WITHDRAWAL,
                amountMinor = rs.getLong("amount_minor"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list()

    /** Mettre de côté : du portefeuille vers l'épargne. */
    @Transactional
    fun deposit(customerId: UUID, idempotencyKey: String, amountMinor: Long): SavingsView {
        val c = active(customerId, amountMinor)
        val walletId = wallet(c.id).id
        val savingsId = openSavings(c.id)
        move(c.id, idempotencyKey, "SAVINGS_DEPOSIT", from = walletId, to = savingsId, amountMinor, "Solde insuffisant sur votre compte")
        return view(c.id)
    }

    /** Reprendre : de l'épargne vers le portefeuille, à tout moment. */
    @Transactional
    fun withdraw(customerId: UUID, idempotencyKey: String, amountMinor: Long): SavingsView {
        val c = active(customerId, amountMinor)
        val walletId = wallet(c.id).id
        val savingsId = savingsAccountId(c.id) ?: throw BusinessException("SAVINGS_INSUFFICIENT", "Vous n'avez pas encore d'épargne")
        move(c.id, idempotencyKey, "SAVINGS_WITHDRAWAL", from = savingsId, to = walletId, amountMinor, "Votre épargne ne contient pas autant")
        return view(c.id)
    }

    private fun active(customerId: UUID, amountMinor: Long): Customer {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val c = customer(customerId)
        if (c.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu ou fermé")
        return c
    }

    private fun move(customerId: UUID, key: String, type: String, from: UUID, to: UUID, amountMinor: Long, insufficient: String) {
        val ledgerKey = "savings:$customerId:$key"
        if (ledgerRepository.findTransactionHeaderByKey(ledgerKey) == null) {
            ledgerRepository.lockAccounts(listOf(from, to))
            if (ledgerRepository.balance(from) < amountMinor) {
                throw BusinessException(if (type == "SAVINGS_DEPOSIT") "INSUFFICIENT_FUNDS" else "SAVINGS_INSUFFICIENT", insufficient)
            }
        }
        ledger.post(
            PostingRequest(
                idempotencyKey = ledgerKey,
                type = type,
                description = null,
                lines = listOf(PostingLine(from, Direction.DEBIT, amountMinor), PostingLine(to, Direction.CREDIT, amountMinor)),
            ),
        )
    }

    @Transactional
    fun setGoal(customerId: UUID, label: String, targetMinor: Long): SavingsView {
        customer(customerId)
        val name = label.trim()
        if (name.isEmpty() || name.length > 40) throw BusinessException("INVALID_GOAL", "Donnez un nom de 1 à 40 caractères à votre objectif")
        if (targetMinor <= 0) throw BusinessException("INVALID_GOAL", "Le montant de l'objectif doit être positif")
        jdbc.sql(
            """INSERT INTO savings_goal (customer_id, label, target_minor, updated_at) VALUES (:c, :l, :t, :now)
               ON CONFLICT (customer_id) DO UPDATE SET label = :l, target_minor = :t, updated_at = :now""",
        ).param("c", customerId).param("l", name).param("t", targetMinor)
            .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update()
        return view(customerId)
    }

    @Transactional
    fun clearGoal(customerId: UUID): SavingsView {
        jdbc.sql("DELETE FROM savings_goal WHERE customer_id = :c").param("c", customerId).update()
        return view(customerId)
    }
}

data class SavingsMoveRequest(@field:Positive val amountMinor: Long)

data class SavingsGoalRequest(@field:Size(max = 40) val label: String, @field:Positive val targetMinor: Long)

@RestController
@RequestMapping("/v1/me/savings")
class SavingsController(private val savings: SavingsService) {
    @GetMapping
    fun view(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = savings.view(customerId)

    @PostMapping("/deposits")
    fun deposit(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: SavingsMoveRequest,
    ) = savings.deposit(customerId, key, body.amountMinor)

    @PostMapping("/withdrawals")
    fun withdraw(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: SavingsMoveRequest,
    ) = savings.withdraw(customerId, key, body.amountMinor)

    @PutMapping("/goal")
    fun setGoal(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @Valid @RequestBody body: SavingsGoalRequest) =
        savings.setGoal(customerId, body.label, body.targetMinor)

    @DeleteMapping("/goal")
    fun clearGoal(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): ResponseEntity<SavingsView> =
        ResponseEntity.ok(savings.clearGoal(customerId))
}
