package cd.capitalinvest.platform.exchange

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Change entre le compte USD et le compte CDF du client.
 * Taux et frais PROVISOIRES : à fixer par la direction générale (et à suivre le marché).
 */
@ConfigurationProperties("capital-invest.exchange")
data class ExchangeProperties(
    /** Francs congolais pour un dollar, appliqué dans les deux sens. */
    val cdfPerUsd: BigDecimal = BigDecimal("2850"),
    /** Frais de change en points de base (100 = 1 %), pris dans la devise de départ. */
    val feeBps: Long = 100,
    /** Plus petit change accepté : 1,00 $ … */
    val minUsdMinor: Long = 100,
    /** … ou 1 000 FC. */
    val minCdfMinor: Long = 1_000,
)

/** Montant en centimes de dollar ou en francs entiers selon [from] et [to]. */
data class ExchangeQuote(
    val from: Currency,
    val to: Currency,
    /** Débité du compte de départ, frais compris. */
    val amountMinor: Long,
    val feeMinor: Long,
    /** Crédité sur le compte d'arrivée. */
    val receivedMinor: Long,
    /** Francs pour un dollar. */
    val rate: BigDecimal,
)

data class ExchangeRateView(val cdfPerUsd: BigDecimal, val feeBps: Long, val minUsdMinor: Long, val minCdfMinor: Long)

data class AccountBalance(val accountId: UUID, val currency: Currency, val balanceMinor: Long)

data class AccountsView(val accounts: List<AccountBalance>)

data class ExchangeResult(
    val exchangeId: UUID,
    /** Opération du grand livre : ouvre le reçu dans l'historique. */
    val transactionId: UUID,
    val quote: ExchangeQuote,
    val accounts: List<AccountBalance>,
    val createdAt: OffsetDateTime,
)

/** Conversion des montants. Les arrondis se font toujours vers le bas, jamais en faveur du client au-delà du taux. */
@Service
class ExchangeRates(private val props: ExchangeProperties) {
    init {
        require(props.cdfPerUsd > BigDecimal.ZERO) { "capital-invest.exchange.cdf-per-usd doit être positif" }
        require(props.feeBps in 0..10_000) { "capital-invest.exchange.fee-bps doit être entre 0 et 10000" }
    }

    val rate: BigDecimal get() = props.cdfPerUsd

    fun view() = ExchangeRateView(props.cdfPerUsd, props.feeBps, props.minUsdMinor, props.minCdfMinor)

    /** Valeur en centimes de dollar d'un montant en francs, pour les plafonds (solde maximal). */
    fun cdfToUsdMinor(francs: Long): Long = convert(Currency.CDF, francs)

    fun quote(from: Currency, amountMinor: Long): ExchangeQuote {
        if (amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        val min = if (from == Currency.USD) props.minUsdMinor else props.minCdfMinor
        if (amountMinor < min) {
            throw BusinessException("EXCHANGE_TOO_SMALL", if (from == Currency.USD) "Changez au moins ${usd(min)}" else "Changez au moins ${cdf(min)}")
        }
        // Frais arrondis à l'unité la plus proche (demi vers le haut), comme les frais d'envoi.
        val fee = Math.multiplyExact(amountMinor, props.feeBps).plus(5_000) / 10_000
        val received = convert(from, amountMinor - fee)
        if (received <= 0) throw BusinessException("EXCHANGE_TOO_SMALL", "Montant trop petit pour être changé")
        return ExchangeQuote(from, other(from), amountMinor, fee, received, props.cdfPerUsd)
    }

    private fun convert(from: Currency, minor: Long): Long {
        val value = BigDecimal.valueOf(minor)
        return when (from) {
            // Centimes de dollar vers francs entiers.
            Currency.USD -> value.multiply(props.cdfPerUsd).divide(HUNDRED, 0, RoundingMode.DOWN)
            // Francs entiers vers centimes de dollar.
            Currency.CDF -> value.multiply(HUNDRED).divide(props.cdfPerUsd, 0, RoundingMode.DOWN)
        }.longValueExact()
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        fun other(c: Currency) = if (c == Currency.USD) Currency.CDF else Currency.USD

        fun usd(minor: Long) = "%d,%02d $".format(minor / 100, minor % 100)

        fun cdf(francs: Long) = "%,d FC".format(java.util.Locale.FRANCE, francs).replace(' ', ' ').replace(' ', ' ')
    }
}

/**
 * Change entre les deux comptes du client. L'argent ne quitte pas Capital Invest : pas de PIN,
 * et les plafonds d'envoi ne s'appliquent pas. Une seule opération du grand livre, équilibrée
 * dans chaque devise, passe par les comptes de position de change.
 */
@Service
class ExchangeService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val rates: ExchangeRates,
    private val clock: Clock,
) {
    private fun systemAccount(code: String) = ledgerRepository.findAccountByCode(code)?.id ?: error("Compte $code absent")

    private fun wallet(customerId: UUID, currency: Currency) = ledgerRepository.findCustomerWallet(customerId, currency)
        ?: throw NotFoundException("ACCOUNT_NOT_FOUND", "Compte ${currency.name} introuvable")

    @Transactional(readOnly = true)
    fun accounts(customerId: UUID): List<AccountBalance> {
        customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        return listOf(Currency.USD, Currency.CDF).map { cur ->
            val w = wallet(customerId, cur)
            AccountBalance(w.id, cur, ledgerRepository.balance(w.id))
        }
    }

    /**
     * [expectedRate] : le taux que l'app a montré. S'il a changé entre-temps, le change est refusé
     * pour que le client ne reçoive jamais un autre montant que celui affiché.
     */
    @Transactional
    fun exchange(customerId: UUID, idempotencyKey: String, from: Currency, amountMinor: Long, expectedRate: BigDecimal?): ExchangeResult {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 200) {
            throw BusinessException("INVALID_IDEMPOTENCY_KEY", "Clé d'idempotence vide ou trop longue")
        }
        val id = UUID.nameUUIDFromBytes("exchange:$customerId:$idempotencyKey".toByteArray())
        val source = wallet(customerId, from)
        val target = wallet(customerId, ExchangeRates.other(from))
        // Verrou des deux comptes d'abord : deux envois simultanés de la même clé passent l'un après l'autre.
        ledgerRepository.lockAccounts(listOf(source.id, target.id))
        find(id)?.let { existing ->
            if (existing.quote.from != from || existing.quote.amountMinor != amountMinor) {
                throw ConflictException("IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour une autre opération")
            }
            return existing.copy(accounts = accounts(customerId))
        }

        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        if (customer.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu ou fermé")
        if (expectedRate != null && expectedRate.compareTo(rates.rate) != 0) {
            throw ConflictException("RATE_CHANGED", "Le taux a changé : 1 $ = ${rates.rate.stripTrailingZeros().toPlainString()} FC. Vérifiez le nouveau montant.")
        }
        val quote = rates.quote(from, amountMinor)
        if (ledgerRepository.balance(source.id) < amountMinor) {
            throw BusinessException("INSUFFICIENT_FUNDS", "Solde ${from.name} insuffisant")
        }

        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        jdbc.sql(
            """INSERT INTO currency_exchange (id, customer_id, from_currency, to_currency, from_minor, fee_minor, to_minor, rate, created_at)
               VALUES (:id, :c, :from, :to, :amount, :fee, :received, :rate, :now)""",
        ).param("id", id).param("c", customerId).param("from", from.name).param("to", quote.to.name)
            .param("amount", amountMinor).param("fee", quote.feeMinor).param("received", quote.receivedMinor)
            .param("rate", quote.rate).param("now", now).update()

        val lines = buildList {
            add(PostingLine(source.id, Direction.DEBIT, amountMinor))
            add(PostingLine(systemAccount("FX_POSITION_${from.name}"), Direction.CREDIT, amountMinor - quote.feeMinor))
            if (quote.feeMinor > 0) add(PostingLine(systemAccount("FEES_REVENUE_${from.name}"), Direction.CREDIT, quote.feeMinor))
            add(PostingLine(systemAccount("FX_POSITION_${quote.to.name}"), Direction.DEBIT, quote.receivedMinor))
            add(PostingLine(target.id, Direction.CREDIT, quote.receivedMinor))
        }
        val posted = ledger.post(
            PostingRequest(
                idempotencyKey = ledgerKey(id),
                type = "EXCHANGE",
                description = null,
                lines = lines,
                crossCurrency = true,
            ),
        )
        return ExchangeResult(id, posted.transaction.id, quote, accounts(customerId), now)
    }

    private fun find(id: UUID): ExchangeResult? =
        jdbc.sql(
            """SELECT x.*, t.id AS transaction_id FROM currency_exchange x
               JOIN ledger_transaction t ON t.idempotency_key = 'exchange:' || x.id
               WHERE x.id = :id""",
        ).param("id", id).query { rs, _ ->
            ExchangeResult(
                exchangeId = id,
                transactionId = rs.getObject("transaction_id", UUID::class.java),
                quote = ExchangeQuote(
                    from = Currency.valueOf(rs.getString("from_currency")),
                    to = Currency.valueOf(rs.getString("to_currency")),
                    amountMinor = rs.getLong("from_minor"),
                    feeMinor = rs.getLong("fee_minor"),
                    receivedMinor = rs.getLong("to_minor"),
                    rate = rs.getBigDecimal("rate").stripTrailingZeros(),
                ),
                accounts = emptyList(),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list().firstOrNull()

    companion object {
        fun ledgerKey(exchangeId: UUID) = "exchange:$exchangeId"
    }
}

data class ExchangeRequest(val from: Currency, @field:Positive val amountMinor: Long, val rate: BigDecimal? = null)

@RestController
@RequestMapping("/v1/me")
class ExchangeController(private val exchange: ExchangeService, private val rates: ExchangeRates) {

    /** Les deux comptes du client, USD puis CDF, avec leur solde. */
    @GetMapping("/accounts")
    fun accounts(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = AccountsView(exchange.accounts(customerId))

    /** Taux du jour et frais, pour calculer l'aperçu dans l'app sans appel à chaque chiffre tapé. */
    @GetMapping("/exchange")
    fun rate() = rates.view()

    @GetMapping("/exchange/quote")
    fun quote(@RequestParam from: Currency, @RequestParam amountMinor: Long) = rates.quote(from, amountMinor)

    @PostMapping("/exchange")
    fun exchange(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: ExchangeRequest,
    ) = exchange.exchange(customerId, key, body.from, body.amountMinor, body.rate)
}
