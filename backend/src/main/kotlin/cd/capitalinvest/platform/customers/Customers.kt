package cd.capitalinvest.platform.customers

import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.ledger.AccountStatus
import cd.capitalinvest.platform.ledger.AccountType
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerAccount
import cd.capitalinvest.platform.ledger.LedgerRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = Customer(
        id = rs.getObject("id", UUID::class.java),
        phone = rs.getString("phone"),
        fullName = rs.getString("full_name"),
        language = Language.valueOf(rs.getString("language")),
        kycLevel = rs.getInt("kyc_level"),
        status = rs.getString("status"),
    )
}

data class CustomerView(val customer: Customer, val walletAccountId: UUID)

@Service
class CustomerService(private val customers: CustomerRepository, private val ledger: LedgerRepository) {

    /** Ouvre un client au niveau KYC 1 avec un portefeuille USD. */
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
        return CustomerView(customer, wallet.id)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): CustomerView {
        val customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val wallet = ledger.findCustomerWallet(id, Currency.USD) ?: error("Client sans portefeuille : $id")
        return CustomerView(customer, wallet.id)
    }
}

data class OpenCustomerRequest(
    @field:Pattern(regexp = "^\\+243[0-9]{9}$", message = "numéro congolais attendu, format +243XXXXXXXXX")
    val phone: String,
    @field:NotBlank @field:Size(max = 120)
    val fullName: String,
    val language: Language = Language.fr,
)

@RestController
@RequestMapping("/v1/customers")
class CustomerController(private val service: CustomerService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@Valid @RequestBody body: OpenCustomerRequest): CustomerView =
        service.open(body.phone, body.fullName, body.language)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): CustomerView = service.get(id)
}
