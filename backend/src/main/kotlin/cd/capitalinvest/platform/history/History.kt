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
import cd.capitalinvest.platform.common.normalizeDrcPhone
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.payments.bills.BillService
import cd.capitalinvest.platform.payments.bills.Biller
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
enum class HistoryKind { TOPUP, TRANSFER_SENT, TRANSFER_RECEIVED, WITHDRAWAL, WITHDRAWAL_REFUND, MERCHANT_PAYMENT, AIRTIME, AIRTIME_REFUND, BILL_PAYMENT, BILL_REFUND, SAVINGS_DEPOSIT, SAVINGS_WITHDRAWAL, TRANSFER_REFUND, POT_CONTRIBUTION, POT_PAYOUT, CARD_PAYMENT, CARD_REFUND, CARD_FEE, ADJUSTMENT }

/** Filtre « type d'opération » de l'écran Historique. */
enum class HistoryCategory(val ledgerTypes: List<String>) {
    TOPUP(listOf("TOPUP")),
    TRANSFER(listOf("TRANSFER", "TRANSFER_INVITE", "TRANSFER_INVITE_CLAIM", "TRANSFER_INVITE_REFUND", "POT_CONTRIBUTION", "POT_PAYOUT")),
    WITHDRAWAL(listOf("WITHDRAWAL", "WITHDRAWAL_SETTLED", "WITHDRAWAL_REFUND")),
    PAYMENT(listOf("MERCHANT_PAYMENT", "AIRTIME", "AIRTIME_REFUND", "BILL_PAYMENT", "BILL_REFUND", "CARD_PAYMENT", "CARD_REFUND")),
    SAVINGS(listOf("SAVINGS_DEPOSIT", "SAVINGS_WITHDRAWAL")),
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
    /** L'autre client pour un envoi (numéro masqué côté app), ou le commerce pour un paiement marchand. */
    val counterpartyName: String?,
    val counterpartyPhone: String?,
    /** Opérateur mobile money (recharge, retrait). */
    val operator: String?,
    /** Numéro mobile money qui a reçu un retrait (+243…). */
    val msisdn: String?,
    /** Réseau du numéro rechargé, pour un achat de crédit (VODACOM, AIRTEL, ORANGE, AFRICELL). */
    val network: String? = null,
    /** Facture : n° de compteur, d'abonné ou de décodeur. */
    val billReference: String? = null,
    /** Facture SNEL prépayée : le code à taper sur le compteur. */
    val token: String? = null,
    /** Code du commerce payé. */
    val merchantCode: String? = null,
    /** Message joint à un envoi ou à un paiement. */
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
    /** Texte cherché : nom ou numéro de l'autre client, commerce, facturier, motif, n° de reçu. */
    val search: String? = null,
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
        val search = q.search?.let(::searchPattern)
        val where = buildList {
            if (q.category != null) add("t.type IN (:types)")
            if (q.from != null) add("t.created_at >= :from")
            if (q.to != null) add("t.created_at < :to")
            if (cursor != null) add("(t.created_at, t.id) < (:cursorAt, :cursorId)")
            if (search != null) add(SEARCH)
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
        if (search != null) spec = spec.param("search", search.like).param("searchDigits", search.digits).param("receipt", search.receipt)
            .param("searchAmount", search.amountMinor)
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
            SELECT t.id, t.type, t.description, t.created_at, t.idempotency_key,
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
               shop.name AS merchant_name, shop.code AS merchant_code,
               tp.operator AS topup_operator,
               w.operator AS withdrawal_operator, w.msisdn, w.status AS withdrawal_status,
               COALESCE(w.failure_message, ap.failure_message, bp.failure_message) AS failure_message,
               COALESCE(w.hold_transaction_id, ap.hold_transaction_id, bp.hold_transaction_id) AS hold_transaction_id,
               bp.biller, bp.reference AS bill_reference, bp.token AS bill_token, bp.status AS bill_status,
               ap.msisdn AS airtime_msisdn, ap.network AS airtime_network, ap.status AS airtime_status,
               pt.status AS invite_status, pt.recipient_name AS invite_name, pt.phone AS invite_phone,
               ps.full_name AS invite_sender_name, ps.phone AS invite_sender_phone,
               pot.title AS pot_title
        FROM tx
        LEFT JOIN LATERAL (
            SELECT c.full_name, c.phone
              FROM ledger_entry oe
              JOIN ledger_account oa ON oa.id = oe.account_id
              JOIN customer c ON c.id = oa.customer_id
             WHERE oe.transaction_id = tx.id AND oa.type = 'CUSTOMER_WALLET' AND oa.id <> :wallet
             LIMIT 1
        ) other ON TRUE
        LEFT JOIN LATERAL (
            SELECT m.name, m.code
              FROM ledger_entry me JOIN merchant m ON m.wallet_account_id = me.account_id
             WHERE me.transaction_id = tx.id
             LIMIT 1
        ) shop ON TRUE
        -- Par la clé aussi : juste après l'écriture (notifications), la recharge n'est pas encore liée à sa transaction.
        LEFT JOIN topup tp ON tp.ledger_transaction_id = tx.id OR tx.idempotency_key = 'topup:' || tp.id
        LEFT JOIN withdrawal w ON w.hold_transaction_id = tx.id OR w.final_transaction_id = tx.id
        -- Par la clé aussi : la notification du remboursement est écrite avant que l'achat soit lié au remboursement.
        LEFT JOIN bill_payment bp ON bp.hold_transaction_id = tx.id OR tx.idempotency_key = 'bill-refund:' || bp.id
        LEFT JOIN airtime_purchase ap ON ap.hold_transaction_id = tx.id OR tx.idempotency_key = 'airtime-refund:' || ap.id
        -- Envoi à un proche sans compte : la clé porte l'identifiant de l'envoi (invite:, invite-claim:, invite-refund:).
        LEFT JOIN pending_transfer pt ON pt.id = CASE WHEN tx.type IN ('TRANSFER_INVITE', 'TRANSFER_INVITE_CLAIM', 'TRANSFER_INVITE_REFUND')
                                                    THEN CAST(split_part(tx.idempotency_key, ':', 2) AS uuid) END
        LEFT JOIN customer ps ON ps.id = pt.sender_id
        -- Cagnotte : participation ou versement à l'organisateur.
        LEFT JOIN LATERAL (
            SELECT p.title FROM ledger_entry pe JOIN pot p ON p.account_id = pe.account_id
             WHERE pe.transaction_id = tx.id LIMIT 1
        ) pot ON TRUE
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
            "TRANSFER_INVITE" -> HistoryKind.TRANSFER_SENT
            "TRANSFER_INVITE_CLAIM" -> HistoryKind.TRANSFER_RECEIVED
            "TRANSFER_INVITE_REFUND" -> HistoryKind.TRANSFER_REFUND
            "WITHDRAWAL" -> HistoryKind.WITHDRAWAL
            "WITHDRAWAL_SETTLED", "WITHDRAWAL_REFUND" -> HistoryKind.WITHDRAWAL_REFUND
            "MERCHANT_PAYMENT" -> HistoryKind.MERCHANT_PAYMENT
            "AIRTIME" -> HistoryKind.AIRTIME
            "AIRTIME_REFUND" -> HistoryKind.AIRTIME_REFUND
            "BILL_PAYMENT" -> HistoryKind.BILL_PAYMENT
            "BILL_REFUND" -> HistoryKind.BILL_REFUND
            "SAVINGS_DEPOSIT" -> HistoryKind.SAVINGS_DEPOSIT
            "SAVINGS_WITHDRAWAL" -> HistoryKind.SAVINGS_WITHDRAWAL
            "POT_CONTRIBUTION" -> HistoryKind.POT_CONTRIBUTION
            "POT_PAYOUT" -> HistoryKind.POT_PAYOUT
            "CARD_PAYMENT" -> HistoryKind.CARD_PAYMENT
            "CARD_REFUND" -> HistoryKind.CARD_REFUND
            "CARD_FEE" -> HistoryKind.CARD_FEE
            else -> HistoryKind.ADJUSTMENT
        }
        // Les frais d'un envoi sont ceux de l'expéditeur : le destinataire ne les voit pas.
        // Un paiement marchand n'a pas de frais pour le client : la commission est payée par le marchand.
        val shownFee = when (kind) {
            HistoryKind.TRANSFER_SENT, HistoryKind.WITHDRAWAL -> fee
            HistoryKind.WITHDRAWAL_REFUND, HistoryKind.TRANSFER_REFUND -> -fee // frais rendus
            else -> 0
        }
        val amount = when (kind) {
            HistoryKind.TRANSFER_SENT, HistoryKind.WITHDRAWAL -> -net - shownFee
            HistoryKind.WITHDRAWAL_REFUND, HistoryKind.TRANSFER_REFUND -> net - shownFee
            else -> kotlin.math.abs(net)
        }
        val status = if (kind == HistoryKind.WITHDRAWAL && isHold) {
            when (rs.getString("withdrawal_status")) {
                "PENDING" -> HistoryStatus.PENDING
                "REVIEW" -> HistoryStatus.REVIEW
                "FAILED" -> HistoryStatus.FAILED
                else -> HistoryStatus.COMPLETED
            }
        } else if (kind == HistoryKind.AIRTIME || kind == HistoryKind.BILL_PAYMENT) {
            when (rs.getString(if (kind == HistoryKind.AIRTIME) "airtime_status" else "bill_status")) {
                "PENDING" -> HistoryStatus.PENDING
                "REVIEW" -> HistoryStatus.REVIEW
                "FAILED" -> HistoryStatus.FAILED
                else -> HistoryStatus.COMPLETED
            }
        } else if (type == "TRANSFER_INVITE" && rs.getString("invite_status") == "PENDING") {
            HistoryStatus.PENDING // le proche n'a pas encore ouvert son compte
        } else {
            HistoryStatus.COMPLETED
        }
        // Envoi à un proche sans compte : l'expéditeur voit le nom qu'il a donné, le proche voit l'expéditeur.
        val invitePhone = if (type == "TRANSFER_INVITE_CLAIM") rs.getString("invite_sender_phone") else rs.getString("invite_phone")
        val otherPhone = rs.getString("other_phone") ?: invitePhone
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
            // Paiement par carte : le nom du commerçant envoyé par le processeur.
            counterpartyName = (if (type == "CARD_PAYMENT" || type == "CARD_REFUND") rs.getString("description") else null)
                ?: rs.getString("pot_title") ?: rs.getString("other_name") ?: rs.getString("merchant_name")
                ?: (if (type == "TRANSFER_INVITE_CLAIM") rs.getString("invite_sender_name") else rs.getString("invite_name"))
                ?: rs.getString("biller")?.let { Biller.valueOf(it).label },
            counterpartyPhone = otherPhone?.let { if (maskPhones) maskPhone(it) else it },
            operator = rs.getString("topup_operator") ?: rs.getString("withdrawal_operator"),
            msisdn = (rs.getString("msisdn") ?: rs.getString("airtime_msisdn"))?.let { "+$it" },
            network = rs.getString("airtime_network"),
            billReference = rs.getString("bill_reference"),
            token = rs.getString("bill_token")?.let { BillService.formatToken(it) },
            merchantCode = rs.getString("merchant_code"),
            note = if (type == "TRANSFER" || type == "TRANSFER_INVITE" || type == "TRANSFER_INVITE_CLAIM" || type == "MERCHANT_PAYMENT" || type == "POT_CONTRIBUTION") rs.getString("description") else null,
            failureMessage = if (status == HistoryStatus.FAILED || kind == HistoryKind.AIRTIME_REFUND || kind == HistoryKind.BILL_REFUND) rs.getString("failure_message") else null,
        )
    }

    /** Ce que le client a tapé, préparé pour chaque façon de retrouver une opération. */
    private class Search(val like: String, val digits: String?, val receipt: String?, val amountMinor: Long?)

    private fun searchPattern(raw: String): Search? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        if (text.length > 60) throw BusinessException("INVALID_SEARCH", "Recherche trop longue (60 caractères au plus)")
        val escaped = plain(text).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        // Un numéro tapé « 0970 000 402 » ou « 970000402 » : on compare les 9 derniers chiffres.
        val digits = text.filter { it.isDigit() }.takeIf { it.length >= 6 }?.let { if (it.length > 9) it.takeLast(9) else it }
        // N° de reçu CI-20261118-3F9A2C : les 6 caractères de fin sont le début de l'identifiant.
        val receipt = Regex("(?i)^CI-?(\\d{8})-?([0-9A-F]{6})$").find(text.replace(" ", ""))?.let { it.groupValues[2].lowercase() }
        // Un montant « 25 » ou « 12,50 » : les opérations de ce montant exact.
        val amount = Regex("^(\\d{1,7})(?:[.,](\\d{1,2}))?\\s*\\$?$").find(text)?.let {
            it.groupValues[1].toLong() * 100 + it.groupValues[2].padEnd(2, '0').ifEmpty { "0" }.toLong()
        }
        return Search("%$escaped%", digits, receipt, amount)
    }

    fun receiptNumber(id: UUID, at: OffsetDateTime): String =
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
        /**
         * Filtre de la recherche, dans la sélection des opérations (avant la pagination) :
         * motif, nom ou numéro de l'autre client, commerce, facturier ou référence, n° de reçu.
         */
        private const val ACCENTS = "àâäáãéèêëíìîïóòôöõúùûüçñ"
        private const val PLAIN = "aaaaaeeeeiiiiooooouuuucn"

        /** Minuscules sans accents, côté base : « Grâce » se trouve en tapant « grace ». */
        private fun norm(column: String) = "translate(lower($column), '$ACCENTS', '$PLAIN')"

        /** Même transformation pour le texte tapé. */
        fun plain(text: String) = text.lowercase().map { c -> ACCENTS.indexOf(c).let { if (it < 0) c else PLAIN[it] } }.joinToString("")

        private val SEARCH = """(
            ${norm("t.description")} LIKE :search
            OR (CAST(:searchAmount AS bigint) IS NOT NULL AND EXISTS (
                SELECT 1 FROM ledger_entry ae WHERE ae.transaction_id = t.id AND ae.account_id = :wallet AND ae.amount_minor = :searchAmount
            ))
            OR (CAST(:receipt AS text) IS NOT NULL AND replace(CAST(t.id AS text), '-', '') LIKE :receipt || '%')
            OR EXISTS (
                SELECT 1 FROM ledger_entry se
                  JOIN ledger_account sa ON sa.id = se.account_id
                  JOIN customer sc ON sc.id = sa.customer_id
                 WHERE se.transaction_id = t.id AND sa.type = 'CUSTOMER_WALLET' AND sa.id <> :wallet
                   AND (${norm("sc.full_name")} LIKE :search OR (CAST(:searchDigits AS text) IS NOT NULL AND sc.phone LIKE '%' || :searchDigits || '%'))
            )
            OR EXISTS (
                SELECT 1 FROM ledger_entry me JOIN merchant m ON m.wallet_account_id = me.account_id
                 WHERE me.transaction_id = t.id AND (${norm("m.name")} LIKE :search OR ${norm("m.code")} LIKE :search)
            )
            OR EXISTS (
                SELECT 1 FROM bill_payment bp
                 WHERE (bp.hold_transaction_id = t.id OR t.idempotency_key = 'bill-refund:' || bp.id)
                   AND (${norm("bp.reference")} LIKE :search OR ${norm("bp.biller")} LIKE :search)
            )
            OR EXISTS (
                SELECT 1 FROM airtime_purchase ap
                 WHERE (ap.hold_transaction_id = t.id OR t.idempotency_key = 'airtime-refund:' || ap.id)
                   AND CAST(:searchDigits AS text) IS NOT NULL AND ap.msisdn LIKE '%' || :searchDigits || '%'
            )
        )"""

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
        @RequestParam(required = false) q: String?,
    ): HistoryPage = history.page(customerId, HistoryQuery(category, direction, from, to, cursor, limit, q), maskPhones = true)

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
    /** Épargne du client, en plus du solde du portefeuille. */
    val savingsMinor: Long = 0,
    /** Bloqué par le client lui-même (téléphone perdu ou volé) : à débloquer après vérification de sa pièce d'identité. */
    val blockedByCustomerAt: java.time.OffsetDateTime? = null,
    /** Plafond d'envoi journalier choisi par le client ; null : celui de son niveau. */
    val personalDailyOutMinor: Long? = null,
    /** Hausse de ce plafond en attente (24 h après la demande). */
    val pendingDailyOut: cd.capitalinvest.platform.limits.PendingDailyLimit? = null,
    /** Compte fermé par le client : date et motif. */
    val closedAt: java.time.OffsetDateTime? = null,
    val closureReason: String? = null,
)

@RestController
@RequestMapping("/v1/backoffice/customers")
class BackofficeCustomerController(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val history: HistoryService,
    private val audit: AuditLog,
    private val limits: cd.capitalinvest.platform.limits.LimitsService,
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
        val c = normalizeDrcPhone(phone)?.let(customers::findByPhone)
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

    private fun summary(c: Customer): CustomerSummary {
        val wallet = ledger.findCustomerWallet(c.id, Currency.USD)
        val personal = limits.personalLimit(c.id)
        val savings = jdbc.sql("SELECT id FROM ledger_account WHERE customer_id = :c AND type = 'CUSTOMER_SAVINGS'")
            .param("c", c.id).query(UUID::class.java).list().sumOf { ledger.balance(it) }
        return CustomerSummary(c.id, c.phone, c.fullName, c.kycLevel, c.status, wallet?.let { ledger.balance(it.id) } ?: 0, savings, c.blockedByCustomerAt,
            personal.dailyOutMinor, personal.pending, c.closedAt, c.closureReason)
    }
}
