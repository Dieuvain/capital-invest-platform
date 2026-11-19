package cd.capitalinvest.platform.payments.airtime

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsService
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Réseaux mobiles de la RDC, reconnus au préfixe du numéro. */
enum class MobileNetwork(val label: String, val prefixes: Set<String>) {
    VODACOM("Vodacom", setOf("81", "82", "83")),
    AIRTEL("Airtel", setOf("97", "98", "99")),
    ORANGE("Orange", setOf("80", "84", "85", "89")),
    AFRICELL("Africell", setOf("90", "91")),
    ;

    companion object {
        /** Réseau d'origine d'un numéro +243… ; null si le préfixe est inconnu (numéro porté, nouveau préfixe). */
        fun guess(phone: String): MobileNetwork? = entries.firstOrNull { phone.removePrefix("+243").take(2) in it.prefixes }
    }
}

enum class AirtimeStatus { PENDING, DELIVERED, FAILED }

@ConfigurationProperties("capital-invest.airtime")
data class AirtimeProperties(
    val minAmountMinor: Long = 50,
    val maxAmountMinor: Long = 5_000,
)

data class AirtimePurchase(
    val id: UUID,
    val network: MobileNetwork,
    /** Numéro rechargé, au format +243…. */
    val phone: String,
    val amountMinor: Long,
    val currency: Currency,
    /** PENDING : le prestataire n'a pas encore confirmé. FAILED : rien n'a été envoyé et le client est remboursé. */
    val status: AirtimeStatus,
    val failureMessage: String?,
    /** Opération du grand livre, pour ouvrir le reçu dans l'historique. */
    val transactionId: UUID,
    val createdAt: OffsetDateTime,
)

/** Réponse du prestataire d'unités. Unknown : pas de réponse sûre, on ne rembourse pas (les unités sont peut-être parties). */
sealed interface AirtimeResult {
    data class Delivered(val reference: String) : AirtimeResult
    data class Failed(val message: String) : AirtimeResult
    data object Unknown : AirtimeResult
}

/** Prestataire qui envoie les unités sur tous les réseaux (agrégateur). */
interface AirtimeConnector {
    val name: String
    fun send(purchaseId: UUID, network: MobileNetwork, msisdn: String, amountMinor: Long): AirtimeResult
}

/**
 * Simulateur, en attendant le contrat avec un agrégateur d'unités.
 * Un numéro qui finit par 0000 est refusé (numéro inactif), ce qui permet d'essayer le remboursement.
 */
@Component
class SimulatorAirtimeConnector : AirtimeConnector {
    override val name = "SIMULATOR"

    override fun send(purchaseId: UUID, network: MobileNetwork, msisdn: String, amountMinor: Long): AirtimeResult =
        if (msisdn.endsWith("0000")) {
            AirtimeResult.Failed("Ce numéro n'est pas actif chez ${network.label}")
        } else {
            AirtimeResult.Delivered("SIM-AT-" + purchaseId.toString().take(8).uppercase())
        }
}

/**
 * Achat de crédit téléphone.
 *
 * 1. Sous verrou du portefeuille et après les plafonds d'envoi : le montant passe du portefeuille au compte du prestataire.
 * 2. Hors transaction : le prestataire envoie les unités.
 * 3. Envoyé → terminé. Refus certain → le montant revient au client. Sans réponse sûre → reste en attente, sans remboursement.
 */
@Service
class AirtimeService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val connector: AirtimeConnector,
    private val props: AirtimeProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val providerAccountId by lazy {
        ledgerRepository.findAccountByCode("AIRTIME_PROVIDER_USD")?.id ?: error("Compte AIRTIME_PROVIDER_USD absent")
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    fun buy(customerId: UUID, idempotencyKey: String, phone: String, network: MobileNetwork?, amountMinor: Long): AirtimePurchase {
        val normalized = normalizeDrcPhone(phone) ?: throw BusinessException("INVALID_PHONE", "Numéro invalide (+243…)")
        val net = network ?: MobileNetwork.guess(normalized)
            ?: throw BusinessException("NETWORK_REQUIRED", "Choisissez le réseau de ce numéro")
        if (amountMinor < props.minAmountMinor || amountMinor > props.maxAmountMinor) {
            throw BusinessException(
                "AIRTIME_AMOUNT", "Le crédit va de ${money(props.minAmountMinor)} à ${money(props.maxAmountMinor)} par achat",
            )
        }
        val (purchase, created) = tx.execute {
            val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
            findByKey(customerId, idempotencyKey)?.let { return@execute it to false }
            if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu")
            val wallet = ledgerRepository.findCustomerWallet(customerId, Currency.USD)!!
            ledgerRepository.lockAccounts(listOf(wallet.id))
            limits.checkOutgoing(customer, wallet.id, amountMinor)
            val posting = ledger.post(
                PostingRequest(
                    idempotencyKey = "airtime:$customerId:$idempotencyKey",
                    type = "AIRTIME",
                    description = "Crédit ${net.label} $normalized",
                    lines = listOf(
                        PostingLine(wallet.id, Direction.DEBIT, amountMinor),
                        PostingLine(providerAccountId, Direction.CREDIT, amountMinor),
                    ),
                ),
            )
            val id = UUID.randomUUID()
            val now = now()
            jdbc.sql(
                """INSERT INTO airtime_purchase (id, customer_id, idempotency_key, network, msisdn, amount_minor, status, provider,
                                                 hold_transaction_id, created_at, updated_at)
                   VALUES (:id, :c, :key, :net, :msisdn, :amount, 'PENDING', :provider, :hold, :now, :now)""",
            ).param("id", id).param("c", customerId).param("key", idempotencyKey).param("net", net.name)
                .param("msisdn", normalized.removePrefix("+")).param("amount", amountMinor).param("provider", connector.name)
                .param("hold", posting.transaction.id).param("now", now).update()
            get(id) to true
        }!!
        if (!created) return purchase
        val result = runCatching { connector.send(purchase.id, net, normalized.removePrefix("+"), amountMinor) }
            .onFailure { log.error("Crédit {} : pas de réponse du prestataire : {}", purchase.id, it.toString()) }
            .getOrDefault(AirtimeResult.Unknown)
        return tx.execute { finish(purchase.id, result) }!!
    }

    private fun finish(id: UUID, result: AirtimeResult): AirtimePurchase {
        val p = jdbc.sql("SELECT * FROM airtime_purchase WHERE id = :id FOR UPDATE").param("id", id).query(::map).single()
        if (p.status != AirtimeStatus.PENDING) return p
        val now = now()
        when (result) {
            is AirtimeResult.Delivered -> jdbc.sql(
                "UPDATE airtime_purchase SET status = 'DELIVERED', provider_reference = :ref, updated_at = :now WHERE id = :id",
            ).param("ref", result.reference).param("now", now).param("id", id).update()
            is AirtimeResult.Failed -> {
                val wallet = ledgerRepository.findCustomerWallet(customerOf(id), Currency.USD)!!
                // Motif enregistré avant l'écriture : la notification du remboursement le reprend.
                jdbc.sql("UPDATE airtime_purchase SET failure_message = :msg WHERE id = :id")
                    .param("msg", result.message.take(300)).param("id", id).update()
                val refund = ledger.post(
                    PostingRequest(
                        idempotencyKey = "airtime-refund:$id",
                        type = "AIRTIME_REFUND",
                        description = result.message.take(140),
                        lines = listOf(
                            PostingLine(providerAccountId, Direction.DEBIT, p.amountMinor),
                            PostingLine(wallet.id, Direction.CREDIT, p.amountMinor),
                        ),
                    ),
                )
                jdbc.sql(
                    "UPDATE airtime_purchase SET status = 'FAILED', refund_transaction_id = :tx, updated_at = :now WHERE id = :id",
                ).param("tx", refund.transaction.id).param("now", now).param("id", id).update()
            }
            AirtimeResult.Unknown -> Unit
        }
        return get(id)
    }

    private fun customerOf(id: UUID): UUID =
        jdbc.sql("SELECT customer_id FROM airtime_purchase WHERE id = :id").param("id", id).query(UUID::class.java).single()

    fun get(id: UUID): AirtimePurchase =
        jdbc.sql("SELECT * FROM airtime_purchase WHERE id = :id").param("id", id).query(::map).optional().orElse(null)
            ?: throw NotFoundException("AIRTIME_NOT_FOUND", "Achat de crédit introuvable")

    fun forCustomer(customerId: UUID, id: UUID): AirtimePurchase =
        jdbc.sql("SELECT * FROM airtime_purchase WHERE id = :id AND customer_id = :c").param("id", id).param("c", customerId)
            .query(::map).optional().orElse(null)
            ?: throw NotFoundException("AIRTIME_NOT_FOUND", "Achat de crédit introuvable")

    /** Les derniers numéros rechargés, pour les proposer en un geste. */
    fun recentNumbers(customerId: UUID): List<RecentAirtimeNumber> =
        jdbc.sql(
            """SELECT msisdn, network, MAX(created_at) AS last FROM airtime_purchase
               WHERE customer_id = :c AND status <> 'FAILED' GROUP BY msisdn, network ORDER BY last DESC LIMIT 5""",
        ).param("c", customerId).query { rs, _ ->
            RecentAirtimeNumber("+" + rs.getString("msisdn"), MobileNetwork.valueOf(rs.getString("network")))
        }.list()

    private fun findByKey(customerId: UUID, key: String): AirtimePurchase? =
        jdbc.sql("SELECT * FROM airtime_purchase WHERE customer_id = :c AND idempotency_key = :k")
            .param("c", customerId).param("k", key).query(::map).optional().orElse(null)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = AirtimePurchase(
        id = rs.getObject("id", UUID::class.java),
        network = MobileNetwork.valueOf(rs.getString("network")),
        phone = "+" + rs.getString("msisdn"),
        amountMinor = rs.getLong("amount_minor"),
        currency = Currency.USD,
        status = AirtimeStatus.valueOf(rs.getString("status")),
        failureMessage = rs.getString("failure_message"),
        transactionId = rs.getObject("hold_transaction_id", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun money(minor: Long) = "%d,%02d \$".format(minor / 100, minor % 100)
}

data class RecentAirtimeNumber(val phone: String, val network: MobileNetwork)

data class AirtimeRequest(
    val phone: String,
    /** Facultatif : deviné d'après le préfixe s'il manque. */
    val network: MobileNetwork? = null,
    @field:Positive val amountMinor: Long,
    /** De l'argent quitte le compte : le PIN est demandé, comme pour un envoi. */
    val pin: String,
)

@RestController
@RequestMapping("/v1/airtime")
class AirtimeController(private val airtime: AirtimeService, private val auth: AuthService) {
    @PostMapping
    fun buy(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: AirtimeRequest,
    ): ResponseEntity<AirtimePurchase> {
        auth.confirmWithPin(customerId, body.pin)
        return ResponseEntity.status(HttpStatus.CREATED).body(airtime.buy(customerId, key, body.phone, body.network, body.amountMinor))
    }

    @GetMapping("/recent")
    fun recent(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = airtime.recentNumbers(customerId)

    @GetMapping("/{id}")
    fun get(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = airtime.forCustomer(customerId, id)
}
