package cd.capitalinvest.platform.customers

import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.ledger.AccountStatus
import cd.capitalinvest.platform.ledger.AccountType
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerAccount
import cd.capitalinvest.platform.ledger.LedgerRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

enum class Language { fr, sw, ln }

data class Customer(
    val id: UUID,
    val phone: String,
    val fullName: String,
    val language: Language,
    val kycLevel: Int,
    val status: String,
    /** Le client a bloqué lui-même son compte (téléphone perdu ou volé). */
    val blockedByCustomerAt: java.time.OffsetDateTime? = null,
    val closedAt: java.time.OffsetDateTime? = null,
    /** Motif donné par le client (NOT_USED, FEES…), suivi de son commentaire. */
    val closureReason: String? = null,
)

@Repository
class CustomerRepository(private val jdbc: JdbcClient) {
    fun insert(c: Customer) {
        jdbc.sql(
            """INSERT INTO customer (id, phone, full_name, language, kyc_level, status)
               VALUES (:id, :phone, :name, :lang, :kyc, :status)""",
        )
            .param("id", c.id).param("phone", c.phone).param("name", c.fullName)
            .param("lang", c.language.name).param("kyc", c.kycLevel).param("status", c.status)
            .update()
    }

    fun findById(id: UUID): Customer? =
        jdbc.sql("SELECT * FROM customer WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    fun findByPhone(phone: String): Customer? =
        jdbc.sql("SELECT * FROM customer WHERE phone = :p").param("p", phone).query(::map).optional().orElse(null)

    fun setPinHash(id: UUID, hash: String) {
        jdbc.sql("UPDATE customer SET pin_hash = :h, pin_failed_attempts = 0, pin_locked_until = NULL WHERE id = :id")
            .param("h", hash).param("id", id).update()
    }

    /** Lit l'état du PIN en verrouillant la ligne, pour compter les échecs sans course. */
    fun lockPinState(id: UUID): PinState? =
        jdbc.sql("SELECT pin_hash, pin_failed_attempts, pin_locked_until FROM customer WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query { rs, _ ->
                PinState(
                    rs.getString("pin_hash"),
                    rs.getInt("pin_failed_attempts"),
                    rs.getObject("pin_locked_until", java.time.OffsetDateTime::class.java),
                )
            }
            .optional().orElse(null)

    fun updatePinFailures(id: UUID, attempts: Int, lockedUntil: java.time.OffsetDateTime?) {
        jdbc.sql("UPDATE customer SET pin_failed_attempts = :a, pin_locked_until = :l WHERE id = :id")
            .param("a", attempts).param("l", lockedUntil).param("id", id).update()
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Customer(
        id = rs.getObject("id", UUID::class.java),
        phone = rs.getString("phone"),
        fullName = rs.getString("full_name"),
        language = Language.valueOf(rs.getString("language")),
        kycLevel = rs.getInt("kyc_level"),
        status = rs.getString("status"),
        blockedByCustomerAt = rs.getObject("blocked_by_customer_at", java.time.OffsetDateTime::class.java),
        closedAt = rs.getObject("closed_at", java.time.OffsetDateTime::class.java),
        closureReason = rs.getString("closure_reason"),
    )
}

data class PinState(val hash: String?, val failedAttempts: Int, val lockedUntil: java.time.OffsetDateTime?)

data class CustomerView(val customer: Customer, val walletAccountId: UUID)

@Service
class CustomerService(private val customers: CustomerRepository, private val ledger: LedgerRepository) {

    /** Ouvre un client au niveau KYC 1 avec un portefeuille USD et un portefeuille CDF. */
    @Transactional
    fun open(phone: String, fullName: String, language: Language): CustomerView {
        if (customers.findByPhone(phone) != null) {
            throw ConflictException("PHONE_ALREADY_REGISTERED", "Ce numéro a déjà un compte")
        }
        val customer = Customer(UUID.randomUUID(), phone, fullName.trim(), language, kycLevel = 1, status = "ACTIVE")
        customers.insert(customer)
        val wallet = LedgerAccount(
            id = UUID.randomUUID(),
            code = null,
            type = AccountType.CUSTOMER_WALLET,
            currency = Currency.USD,
            normalSide = Direction.CREDIT, // dette de Capital Invest envers le client
            allowNegative = false,
            customerId = customer.id,
            status = AccountStatus.ACTIVE,
        )
        ledger.createAccount(wallet)
        // Compte en francs congolais : vide à l'ouverture, alimenté par un change depuis le compte USD.
        ledger.createAccount(wallet.copy(id = UUID.randomUUID(), currency = Currency.CDF))
        return CustomerView(customer, wallet.id)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): CustomerView {
        val customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val wallet = ledger.findCustomerWallet(id, Currency.USD) ?: error("Client sans portefeuille : $id")
        return CustomerView(customer, wallet.id)
    }
}
