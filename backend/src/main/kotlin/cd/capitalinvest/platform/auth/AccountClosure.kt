package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.notifications.NotificationService
import cd.capitalinvest.platform.notifications.NotificationService.Companion.money
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Ce qui empêche encore de fermer le compte, avec ce que le client doit faire. */
data class ClosureBlocker(val code: String, val message: String)

data class ClosureCheck(val canClose: Boolean, val balanceMinor: Long, val savingsMinor: Long, val blockers: List<ClosureBlocker>)

enum class ClosureReason { NOT_USED, FEES, OTHER_SERVICE, MOVING, OTHER }

data class CloseAccountRequest(val pin: String, val reason: ClosureReason, @field:Size(max = 300) val comment: String? = null)

/**
 * Fermeture du compte par le client. Il faut un compte vide (portefeuille et épargne) et plus rien en cours :
 * aucun argent ne reste bloqué sur un compte fermé. Les envois programmés sont arrêtés, toutes les sessions
 * fermées. Les opérations et le dossier KYC restent conservés ; le numéro reste attaché au compte fermé.
 */
@Service
class AccountClosureService(
    private val jdbc: JdbcClient,
    private val auth: AuthService,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val notifications: NotificationService,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun count(sql: String, customerId: UUID): Int =
        jdbc.sql(sql).param("c", customerId).query(Int::class.java).single()

    @Transactional(readOnly = true)
    fun check(customerId: UUID): ClosureCheck {
        val wallet = ledger.findCustomerWallet(customerId, Currency.USD) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val balance = ledger.balance(wallet.id)
        val francs = ledger.findCustomerWallet(customerId, Currency.CDF)?.let { ledger.balance(it.id) } ?: 0
        val savings = jdbc.sql("SELECT id FROM ledger_account WHERE customer_id = :c AND type = 'CUSTOMER_SAVINGS'")
            .param("c", customerId).query(UUID::class.java).list().sumOf { ledger.balance(it) }
        val blockers = buildList {
            if (savings > 0) add(ClosureBlocker("SAVINGS_NOT_EMPTY", "Reprenez votre épargne (${money(savings)}) sur votre solde."))
            if (balance > 0) add(ClosureBlocker("BALANCE_NOT_EMPTY", "Retirez ou envoyez votre solde (${money(balance)}) : un compte fermé ne peut plus rien rendre."))
            if (francs > 0) {
                add(ClosureBlocker("CDF_BALANCE_NOT_EMPTY", "Changez vos ${cd.capitalinvest.platform.exchange.ExchangeRates.cdf(francs)} en dollars, puis retirez-les."))
            }
            if (count("SELECT count(*) FROM topup WHERE customer_id = :c AND status = 'PENDING'", customerId) > 0) {
                add(ClosureBlocker("TOPUP_PENDING", "Une recharge est encore en attente de l'opérateur."))
            }
            val pending = count(
                """SELECT (SELECT count(*) FROM withdrawal WHERE customer_id = :c AND status IN ('PENDING', 'REVIEW'))
                        + (SELECT count(*) FROM airtime_purchase WHERE customer_id = :c AND status IN ('PENDING', 'REVIEW'))
                        + (SELECT count(*) FROM bill_payment WHERE customer_id = :c AND status IN ('PENDING', 'REVIEW'))""",
                customerId,
            )
            if (pending > 0) add(ClosureBlocker("OPERATION_PENDING", "Une opération est encore en cours. Attendez sa confirmation."))
            if (count("SELECT count(*) FROM pending_transfer WHERE sender_id = :c AND status = 'PENDING'", customerId) > 0) {
                add(ClosureBlocker("PENDING_TRANSFER_OPEN", "Un envoi à un proche sans compte attend encore. Annulez-le pour reprendre l'argent."))
            }
            if (count("SELECT count(*) FROM credit WHERE customer_id = :c AND status IN ('PENDING_REVIEW', 'ACTIVE', 'LATE', 'DEFAULTED')", customerId) > 0) {
                add(ClosureBlocker("CREDIT_OPEN", "Remboursez votre crédit express, ou retirez votre demande en examen."))
            }
            jdbc.sql("SELECT title FROM pot WHERE owner_id = :c AND status = 'OPEN' ORDER BY created_at").param("c", customerId)
                .query(String::class.java).list().firstOrNull()?.let {
                    add(ClosureBlocker("POT_OPEN", "Clôturez votre cagnotte « $it » : l'argent collecté passera sur votre solde."))
                }
        }
        return ClosureCheck(blockers.isEmpty(), balance, savings, blockers)
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun close(customerId: UUID, pin: String, reason: ClosureReason, comment: String?) {
        auth.confirmWithPin(customerId, pin)
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Compte suspendu ou fermé", HttpStatus.FORBIDDEN)
        // Le portefeuille est verrouillé : aucun argent ne peut arriver entre la vérification et la fermeture.
        jdbc.sql("SELECT id FROM ledger_account WHERE customer_id = :c FOR UPDATE").param("c", customerId).query(UUID::class.java).list()
        val check = check(customerId)
        if (!check.canClose) throw BusinessException(check.blockers.first().code, check.blockers.first().message)
        val now = now()
        val text = listOfNotNull(reason.name, comment?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" : ")
        jdbc.sql("UPDATE customer SET status = 'CLOSED', closed_at = :now, closure_reason = :r WHERE id = :id")
            .param("now", now).param("r", text).param("id", customerId).update()
        jdbc.sql("UPDATE ledger_account SET status = 'CLOSED' WHERE customer_id = :c").param("c", customerId).update()
        jdbc.sql("UPDATE scheduled_transfer SET status = 'CANCELLED' WHERE customer_id = :c AND status <> 'CANCELLED'").param("c", customerId).update()
        jdbc.sql("UPDATE money_request SET status = 'CANCELLED' WHERE requester_id = :c AND status = 'PENDING'").param("c", customerId).update()
        jdbc.sql("UPDATE session SET revoked_at = :now WHERE customer_id = :c AND revoked_at IS NULL").param("now", now).param("c", customerId).update()
        jdbc.sql("UPDATE biometric_key SET revoked_at = :now WHERE customer_id = :c AND revoked_at IS NULL").param("now", now).param("c", customerId).update()
        audit.record(ActorType.CUSTOMER, customerId, "CUSTOMER_CLOSED", "CUSTOMER", customerId, text)
        notifications.accountClosed(customerId, now)
    }
}

@RestController
@RequestMapping("/v1/me/closure")
class AccountClosureController(private val service: AccountClosureService) {

    /** Ce qu'il reste à faire avant de pouvoir fermer le compte. */
    @GetMapping
    fun check(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = service.check(customerId)

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun close(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @Valid @RequestBody b: CloseAccountRequest) =
        service.close(customerId, b.pin, b.reason, b.comment)
}
