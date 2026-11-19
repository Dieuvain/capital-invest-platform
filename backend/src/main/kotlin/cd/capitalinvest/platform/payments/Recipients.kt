package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.LedgerRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.recipients")
data class RecipientsProperties(
    /** Recherches de destinataire par client et par heure : assez pour envoyer, trop peu pour collecter les noms. */
    val maxLookupsPerHour: Int = 30,
)

/** Destinataire tel que l'envoyeur le voit avant de confirmer : prénom et initiale, assez pour reconnaître un proche. */
data class Recipient(
    val phone: String,
    /** « Béni M. » */
    val displayName: String,
)

@Service
class RecipientService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val props: RecipientsProperties,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /**
     * Vérifie le numéro avant un envoi. Le nom évite d'envoyer au mauvais numéro (une erreur fréquente et sans retour) ;
     * il est réduit au prénom et à une initiale, et les recherches sont limitées par heure.
     * Sans transaction : une recherche sur un numéro inconnu reste comptée.
     */
    fun lookup(customerId: UUID, rawPhone: String): Recipient {
        val since = now().minus(Duration.ofHours(1))
        val used = jdbc.sql("SELECT count(*) FROM recipient_lookup WHERE customer_id = :c AND created_at > :since")
            .param("c", customerId).param("since", since).query(Int::class.java).single()
        if (used >= props.maxLookupsPerHour) {
            throw BusinessException("RECIPIENT_LOOKUP_LIMIT", "Trop de numéros vérifiés en une heure. Réessayez plus tard.", HttpStatus.TOO_MANY_REQUESTS)
        }
        jdbc.sql("INSERT INTO recipient_lookup (customer_id, created_at) VALUES (:c, :now)").param("c", customerId).param("now", now()).update()
        val phone = normalizeDrcPhone(rawPhone) ?: throw BusinessException("INVALID_PHONE", "Numéro congolais attendu (+243…)")
        val recipient = customers.findByPhone(phone) ?: throw NotFoundException("RECIPIENT_NOT_FOUND", "Aucun client Capital Invest avec ce numéro")
        if (recipient.id == customerId) throw BusinessException("SAME_ACCOUNT", "Impossible d'envoyer à soi-même")
        if (recipient.status != "ACTIVE") throw BusinessException("RECIPIENT_NOT_ACTIVE", "Ce compte ne peut pas recevoir d'argent pour le moment")
        return Recipient(phone, displayName(recipient.fullName))
    }

    /** Les derniers clients à qui il a envoyé de l'argent : son numéro et le nom qu'il a déjà vu. */
    fun recent(customerId: UUID, limit: Int = 5): List<Recipient> {
        val wallet = ledger.findCustomerWallet(customerId, Currency.USD) ?: return emptyList()
        return jdbc.sql(
            """SELECT c.full_name, c.phone, max(t.created_at) AS last
               FROM ledger_transaction t
               JOIN ledger_entry d ON d.transaction_id = t.id AND d.account_id = :wallet AND d.direction = 'DEBIT'
               JOIN ledger_entry e ON e.transaction_id = t.id AND e.direction = 'CREDIT'
               JOIN ledger_account a ON a.id = e.account_id AND a.type = 'CUSTOMER_WALLET' AND a.customer_id <> :me
               JOIN customer c ON c.id = a.customer_id
               WHERE t.type = 'TRANSFER' AND c.status = 'ACTIVE'
               GROUP BY c.id, c.full_name, c.phone
               ORDER BY last DESC LIMIT :limit""",
        ).param("wallet", wallet.id).param("me", customerId).param("limit", limit)
            .query { rs, _ -> Recipient(rs.getString("phone"), displayName(rs.getString("full_name"))) }.list()
    }

    companion object {
        /** « Béni Mukendi Kalala » → « Béni M. » ; un seul mot reste tel quel. */
        fun displayName(fullName: String): String {
            val parts = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when (parts.size) {
                0 -> "Client Capital Invest"
                1 -> parts[0]
                else -> "${parts[0]} ${parts[1].first().uppercaseChar()}."
            }
        }
    }
}

@RestController
@RequestMapping("/v1/transfers")
class RecipientController(private val recipients: RecipientService) {
    @GetMapping("/recipient")
    fun lookup(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestParam phone: String) = recipients.lookup(customerId, phone)

    @GetMapping("/recent")
    fun recent(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = recipients.recent(customerId)
}
