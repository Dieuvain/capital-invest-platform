package cd.capitalinvest.platform.history

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.limits.LimitsProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/** Ce que le client voit d'une opération : une ligne par opération, pas par écriture du grand livre. */
enum class HistoryKind { TOPUP, TRANSFER_SENT, TRANSFER_RECEIVED, WITHDRAWAL, WITHDRAWAL_REFUND, ADJUSTMENT }

/** Filtre « type d'opération » de l'écran Historique. */
enum class HistoryCategory(val ledgerTypes: List<String>) {
    TOPUP(listOf("TOPUP")),
    TRANSFER(listOf("TRANSFER")),
    WITHDRAWAL(listOf("WITHDRAWAL", "WITHDRAWAL_SETTLED", "WITHDRAWAL_REFUND")),
}

enum class HistoryDirection { IN, OUT }

/** COMPLETED : terminé. PENDING / REVIEW : retrait pas encore versé. FAILED : retrait refusé (remboursé à part). */
enum class HistoryStatus { COMPLETED, PENDING, REVIEW, FAILED }

data class HistoryItem(
    /** Identifiant de l'opération (transaction du grand livre), pour ouvrir le reçu. */
    val id: UUID,
    /** Numéro lisible, à donner au service client. */
    val receiptNumber: String,
    val kind: HistoryKind,
    val status: HistoryStatus,
    /** Montant de l'opération, sans les frais. */
    val amountMinor: Long,
    val feeMinor: Long,
    /** Effet sur le solde : positif quand l'argent entre. */
    val netMinor: Long,
    val currency: Currency,
    val createdAt: OffsetDateTime,
    /** L'autre client, pour un envoi. Numéro masqué côté app. */
    val counterpartyName: String?,
    val counterpartyPhone: String?,
    /** Opérateur mobile money (recharge, retrait). */
    val operator: String?,
    /** Numéro mobile money qui a reçu un retrait (+243…). */
    val msisdn: String?,
    /** Message joint à un envoi. */
    val note: String?,
    val failureMessage: String?,
)

data class HistoryPage(val items: List<HistoryItem>, val nextCursor: String?)

/** Reçu d'une opération, tel qu'il peut être montré ou partagé. */
data class Receipt(
    val item: HistoryItem,
    val holderName: String,
    val holderPhone: String,
    val issuedAt: OffsetDateTime,
)

data class HistoryQuery(
    val category: HistoryCategory? = null,
    val direction: HistoryDirection? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val cursor: String? = null,
    val limit: Int = 30,
)

@Service
class HistoryService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private val zone get() = ZoneId.of(limits.timezone)

    private fun wallet(customerId: UUID): UUID =
        ledger.findCustomerWallet(customerId, Currency.USD)?.id
            ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    /**
     * Opérations qui ont touché le portefeuille, de la plus récente à la plus ancienne.
     * Pagination par curseur : stable même quand de nouvelles opérations arrivent pendant la lecture.
     */
    fun page(customerId: UUID, q: HistoryQuery, maskPhones: Boolean): HistoryPage {
        if (q.from != null && q.to != null && q.to.isBefore(q.from)) {
            throw BusinessException("INVALID_PERIOD", "La date de fin est avant la date de début")
        }
        val limit = q.limit.coerceIn(1, 100)
        val cursor = q.cursor?.let(::decodeCursor)
        val where = buildList {
            if (q.category != null) add("t.type IN (:types)")
            if (q.from != null) add("t.created_at >= :from")
            if (q.to != null) add("t.created_at < :to")
            if (cursor != null) add("(t.created_at, t.id) < (:cursorAt, :cursorId)")
        }
        val having = when (q.direction) {
            HistoryDirection.IN -> "HAVING SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END) > 0"
            HistoryDirection.OUT -> "HAVING SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END) < 0"
            null -> ""
        }
        var spec = jdbc.sql(select(where.joinToString(" AND ").ifEmpty { "TRUE" }, having))
            .param("wallet", wallet(customerId))
            .param("limit", limit + 1)
        if (q.category != null) spec = spec.param("types", q.category.ledgerTypes)
        if (q.from != null) spec = spec.param("from", q.from.atStartOfDay(zone).toOffsetDateTime())
        if (q.to != null) spec = spec.param("to", q.to.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
        if (cursor != null) spec = spec.param("cursorAt", cursor.first).param("cursorId", cursor.second)
        val rows = spec.query { rs, _ -> toItem(rs, maskPhones) }.list()
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let { encodeCursor(it.createdAt, it.id) } else null
        return HistoryPage(items, next)
    }

    /** Reçu d'une opération du client. Une opération qui ne touche pas son portefeuille n'existe pas pour lui. */
    fun receipt(customerId: UUID, transactionId: UUID, maskPhones: Boolean): Receipt {
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val item = jdbc.sql(select("t.id = :tx", ""))
            .param("wallet", wallet(customerId)).param("limit", 1).param("tx", transactionId)
            .query { rs, _ -> toItem(rs, maskPhones) }.list().firstOrNull()
            ?: throw NotFoundException("OPERATION_NOT_FOUND", "Opération introuvable")
        return Receipt(item, customer.fullName, customer.phone, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
    }

    private fun select(where: String, having: String) = """
        WITH tx AS (
            SELECT t.id, t.type, t.description, t.created_at,
                   SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END) AS net,
                   MIN(e.currency) AS currency
            FROM ledger_transaction t
            JOIN ledger_entry e ON e.transaction_id = t.id AND e.account_id = :wallet
            WHERE $where
            GROUP BY t.id
            $having
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT :limit
        )
        SELECT tx.*,
               (SELECT COALESCE(SUM(CASE WHEN fe.direction = 'CREDIT' THEN fe.amount_minor ELSE -fe.amount_minor END), 0)
                  FROM ledger_entry fe JOIN ledger_account fa ON fa.id = fe.account_id
                 WHERE fe.transaction_id = tx.id AND fa.type = 'FEES_REVENUE') AS fee,
               other.full_name AS other_name, other.phone AS other_phone,
               tp.operator AS topup_operator,
               w.operator AS withdrawal_operator, w.msisdn, w.status AS withdrawal_status,
               w.failure_message, w.hold_transaction_id
        FROM tx
        LEFT JOIN LATERAL (
            SELECT c.full_name, c.phone
              FROM ledger_entry oe
              JOIN ledger_account oa ON oa.id = oe.account_id
              JOIN customer c ON c.id = oa.customer_id
             WHERE oe.transaction_id = tx.id AND oa.type = 'CUSTOMER_WALLET' AND oa.id <> :wallet
             LIMIT 1
        ) other ON TRUE
        LEFT JOIN topup tp ON tp.ledger_transaction_id = tx.id
        LEFT JOIN withdrawal w ON w.hold_transaction_id = tx.id OR w.final_transaction_id = tx.id
        ORDER BY tx.created_at DESC, tx.id DESC
    """

    private fun toItem(rs: ResultSet, maskPhones: Boolean): HistoryItem {
        val id = rs.getObject("id", UUID::class.java)
        val type = rs.getString("type")
        val net = rs.getLong("net")
        val fee = rs.getLong("fee")
        val createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        val isHold = rs.getObject("hold_transaction_id", UUID::class.java) == id
        val kind = when (type) {
            "TOPUP" -> HistoryKind.TOPUP
            "TRANSFER" -> if (net < 0) HistoryKind.TRANSFER_SENT else HistoryKind.TRANSFER_RECEIVED
            "WITHDRAWAL" -> HistoryKind.WITHDRAWAL
            "WITHDRAWAL_SETTLED", "WITHDRAWAL_REFUND" -> HistoryKind.WITHDRAWAL_REFUND
            else -> HistoryKind.ADJUSTMENT
        }
        // Les frais d'un envoi sont ceux de l'expéditeur : le destinataire ne les voit pas.
        val shownFee = when (kind) {
            HistoryKind.TRANSFER_SENT, HistoryKind.WITHDRAWAL -> fee
            HistoryKind.WITHDRAWAL_REFUND -> -fee // frais rendus
            else -> 0
        }
        val amount = when (kind) {
            HistoryKind.TRANSFER_SENT, HistoryKind.WITHDRAWAL -> -net - shownFee
            HistoryKind.WITHDRAWAL_REFUND -> net - shownFee
            else -> kotlin.math.abs(net)
        }
        val status = if (kind == HistoryKind.WITHDRAWAL && isHold) {
            when (rs.getString("withdrawal_status")) {
                "PENDING" -> HistoryStatus.PENDING
                "REVIEW" -> HistoryStatus.REVIEW
                "FAILED" -> HistoryStatus.FAILED
                else -> HistoryStatus.COMPLETED
            }
        } else {
            HistoryStatus.COMPLETED
        }
        val otherPhone = rs.getString("other_phone")
        return HistoryItem(
            id = id,
            receiptNumber = receiptNumber(id, createdAt),
            kind = kind,
            status = status,
            amountMinor = amount,
            feeMinor = shownFee,
            netMinor = net,
            currency = Currency.valueOf(rs.getString("currency")),
            createdAt = createdAt,
            counterpartyName = rs.getString("other_name"),
            counterpartyPhone = otherPhone?.let { if (maskPhones) maskPhone(it) else it },
            operator = rs.getString("topup_operator") ?: rs.getString("withdrawal_operator"),
            msisdn = rs.getString("msisdn")?.let { "+$it" },
            note = if (type == "TRANSFER") rs.getString("description") else null,
            failureMessage = if (status == HistoryStatus.FAILED) rs.getString("failure_message") else null,
        )
    }

    private fun receiptNumber(id: UUID, at: OffsetDateTime): String =
        "CI-" + at.atZoneSameInstant(zone).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" +
            id.toString().replace("-", "").take(6).uppercase()

    private fun encodeCursor(at: OffsetDateTime, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray())

    private fun decodeCursor(c: String): Pair<OffsetDateTime, UUID> = try {
        val (at, id) = String(Base64.getUrlDecoder().decode(c)).split("|", limit = 2)
        OffsetDateTime.parse(at) to UUID.fromString(id)
    } catch (e: Exception) {
        throw BusinessException("INVALID_CURSOR", "Curseur de pagination invalide")
    }

    companion object {
        /** +243970000402 devient +243 97• ••• 402 : assez pour reconnaître un proche, pas pour recopier le numéro. */
        fun maskPhone(phone: String): String =
            if (phone.length < 8) phone else "${phone.take(4)} ${phone.substring(4, 6)}• ••• ${phone.takeLast(3)}"
    }
}

@RestController
@RequestMapping("/v1/me/history")
class HistoryController(private val history: HistoryService) {
    @GetMapping
    fun page(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestParam(required = false) category: HistoryCategory?,
        @RequestParam(required = false) direction: HistoryDirection?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") limit: Int,
    ): HistoryPage = history.page(customerId, HistoryQuery(category, direction, from, to, cursor, limit), maskPhones = true)

    @GetMapping("/{id}")
    fun receipt(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID): Receipt =
        history.receipt(customerId, id, maskPhones = true)
}

/** Fiche client du back-office, pour répondre à une réclamation. */
data class CustomerSummary(
    val id: UUID,
    val phone: String,
    val fullName: String,
    val kycLevel: Int,
    val status: String,
    val balanceMinor: Long,
)

@RestController
@RequestMapping("/v1/backoffice/customers")
class BackofficeCustomerController(
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val history: HistoryService,
    private val audit: AuditLog,
) {
    /** Conformité et administrateurs : les autres rôles n'ont pas à lire les opérations des clients. */
    private fun requireAccess(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    private fun customer(id: UUID): Customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    /** Recherche par numéro exact (+243…). Chaque consultation est inscrite au journal d'audit. */
    @GetMapping
    fun find(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @RequestParam phone: String): CustomerSummary {
        requireAccess(me)
        val c = normalizePhone(phone)?.let(customers::findByPhone)
            ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Aucun client avec ce numéro")
        audit.record(ActorType.AGENT, me.id, "CUSTOMER_VIEWED", "CUSTOMER", c.id)
        return summary(c)
    }

    @GetMapping("/{id}")
    fun get(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID): CustomerSummary {
        requireAccess(me)
        return summary(customer(id))
    }

    @GetMapping("/{id}/history")
    fun page(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @RequestParam(required = false) category: HistoryCategory?,
        @RequestParam(required = false) direction: HistoryDirection?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "30") limit: Int,
    ): HistoryPage {
        requireAccess(me)
        return history.page(customer(id).id, HistoryQuery(category, direction, from, to, cursor, limit), maskPhones = false)
    }

    @GetMapping("/{id}/history/{transactionId}")
    fun receipt(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @PathVariable transactionId: UUID): Receipt {
        requireAccess(me)
        return history.receipt(customer(id).id, transactionId, maskPhones = false)
    }

    /** « +243 970 000 001 », « 243970000001 », « 0970000001 » ou « 970000001 » vers +243970000001. */
    private fun normalizePhone(input: String): String? {
        val digits = input.filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("243") -> "+$digits"
            digits.length == 10 && digits.startsWith("0") -> "+243${digits.drop(1)}"
            digits.length == 9 -> "+243$digits"
            else -> null
        }
    }

    private fun summary(c: Customer): CustomerSummary {
        val wallet = ledger.findCustomerWallet(c.id, Currency.USD)
        return CustomerSummary(c.id, c.phone, c.fullName, c.kycLevel, c.status, wallet?.let { ledger.balance(it.id) } ?: 0)
    }
}
