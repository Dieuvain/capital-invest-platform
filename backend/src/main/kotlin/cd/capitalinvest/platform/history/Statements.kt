package cd.capitalinvest.platform.history

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.notifications.NotificationService.Companion.money
import cd.capitalinvest.platform.notifications.NotificationService.Companion.operatorLabel
import com.lowagie.text.Chunk
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.FontFactory
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfWriter
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/** Un mois que le client peut demander. [partial] : le mois en cours, arrêté à aujourd'hui. */
data class StatementMonth(val month: YearMonth, val partial: Boolean)

/** Relevé d'un mois : solde d'ouverture, chaque opération dans l'ordre, solde de clôture. */
data class Statement(
    val month: YearMonth,
    val from: LocalDate,
    /** Dernier jour couvert : fin du mois, ou aujourd'hui pour le mois en cours. */
    val to: LocalDate,
    val partial: Boolean,
    val holderName: String,
    val holderPhone: String,
    val currency: Currency,
    val openingBalanceMinor: Long,
    val totalInMinor: Long,
    /** Sorties, frais compris (nombre positif). */
    val totalOutMinor: Long,
    /** Frais payés sur la période (envois, retraits), nets des frais rendus. */
    val feesMinor: Long,
    val closingBalanceMinor: Long,
    /** Du plus ancien au plus récent. */
    val items: List<HistoryItem>,
    val issuedAt: OffsetDateTime,
)

/**
 * Relevés de compte mensuels du portefeuille : à l'écran ou en PDF, pour tenir ses comptes ou prouver ses
 * revenus (bailleur, banque, KYC). Les montants viennent du grand livre, comme l'historique.
 */
@Service
class StatementService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledger: LedgerRepository,
    private val history: HistoryService,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private val zone get() = ZoneId.of(limits.timezone)

    private fun today() = LocalDate.now(clock.withZone(zone))

    private fun wallet(customerId: UUID) = ledger.findCustomerWallet(customerId, Currency.USD)
        ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    private fun openedMonth(walletId: UUID): YearMonth {
        val created = jdbc.sql("SELECT created_at FROM ledger_account WHERE id = :id").param("id", walletId)
            .query(OffsetDateTime::class.java).single()
        return YearMonth.from(created.atZoneSameInstant(zone))
    }

    /** Les mois depuis l'ouverture du compte, le plus récent d'abord (24 au plus). */
    fun months(customerId: UUID): List<StatementMonth> {
        val current = YearMonth.from(today())
        val first = openedMonth(wallet(customerId).id)
        return generateSequence(current) { it.minusMonths(1) }
            .takeWhile { !it.isBefore(first) }
            .take(24)
            .map { StatementMonth(it, it == current) }
            .toList()
    }

    fun statement(customerId: UUID, month: YearMonth): Statement {
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val walletId = wallet(customerId).id
        val today = today()
        val current = YearMonth.from(today)
        if (month.isAfter(current) || month.isBefore(openedMonth(walletId))) {
            throw BusinessException("STATEMENT_UNAVAILABLE", "Aucun relevé pour ce mois : le compte n'était pas ouvert")
        }
        val from = month.atDay(1)
        val to = if (month == current) today else month.atEndOfMonth()
        val start = from.atStartOfDay(zone).toOffsetDateTime()

        val opening = jdbc.sql(
            """SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
               FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
               WHERE e.account_id = :w AND t.created_at < :start""",
        ).param("w", walletId).param("start", start).query(Long::class.java).single()

        val items = mutableListOf<HistoryItem>()
        var cursor: String? = null
        do {
            val page = history.page(customerId, HistoryQuery(from = from, to = to, cursor = cursor, limit = 100), maskPhones = true)
            items += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        items.reverse()

        val totalIn = items.filter { it.netMinor > 0 }.sumOf { it.netMinor }
        val totalOut = -items.filter { it.netMinor < 0 }.sumOf { it.netMinor }
        return Statement(
            month = month,
            from = from,
            to = to,
            partial = month == current,
            holderName = customer.fullName,
            holderPhone = customer.phone,
            currency = Currency.USD,
            openingBalanceMinor = opening,
            totalInMinor = totalIn,
            totalOutMinor = totalOut,
            feesMinor = items.sumOf { it.feeMinor },
            closingBalanceMinor = opening + totalIn - totalOut,
            items = items,
            issuedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
        )
    }

    fun pdf(s: Statement): ByteArray = StatementPdf(s, zone).render()
}

/** Mise en page A4 du relevé : en-tête, résumé, opérations avec solde après chaque ligne, pied de page numéroté. */
private class StatementPdf(private val s: Statement, private val zone: ZoneId) {
    private val ink = Color(0x23, 0x1F, 0x20)
    private val muted = Color(0x6B, 0x65, 0x60)
    private val line = Color(0xE2, 0xDE, 0xD8)
    private val tint = Color(0xF5, 0xF3, 0xEF)
    private val green = Color(0x1F, 0x7A, 0x4D)

    private fun font(size: Float, bold: Boolean = false, color: Color = ink): Font =
        FontFactory.getFont(if (bold) FontFactory.HELVETICA_BOLD else FontFactory.HELVETICA, "Cp1252", false, size, Font.NORMAL, color)

    private val fr = Locale.FRENCH
    private val day = DateTimeFormatter.ofPattern("d MMMM yyyy", fr)
    private val dayShort = DateTimeFormatter.ofPattern("dd/MM", fr)
    private val time = DateTimeFormatter.ofPattern("HH:mm", fr)
    private val stamp = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", fr)

    /** « 1er novembre 2026 », « 19 novembre 2026 ». */
    private fun long(d: LocalDate) = if (d.dayOfMonth == 1) "1er " + d.format(DateTimeFormatter.ofPattern("MMMM yyyy", fr)) else d.format(day)

    private fun signed(minor: Long) = if (minor > 0) "+${money(minor)}" else money(minor)

    fun render(): ByteArray {
        val out = ByteArrayOutputStream()
        val doc = Document(PageSize.A4, 40f, 40f, 40f, 56f)
        val writer = PdfWriter.getInstance(doc, out)
        writer.pageEvent = Footer()
        doc.addTitle("Relevé de compte ${monthName()}")
        doc.addAuthor("Capital Invest")
        doc.open()

        doc.add(header())
        doc.add(Paragraph(" ", font(6f)))
        doc.add(summary())
        doc.add(Paragraph(" ", font(8f)))
        doc.add(operations())
        if (s.items.isEmpty()) {
            doc.add(Paragraph("Aucune opération sur la période.", font(10f, color = muted)).apply { spacingBefore = 10f })
        }
        doc.close()
        return out.toByteArray()
    }

    private fun monthName() = s.month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", fr)).replaceFirstChar { it.uppercase() }

    private fun header(): PdfPTable {
        val t = PdfPTable(floatArrayOf(1.2f, 1f)).apply { widthPercentage = 100f }
        val left = PdfPCell().apply { border = Rectangle.NO_BORDER }
        left.addElement(Paragraph("Capital Invest", font(18f, bold = true)))
        left.addElement(Paragraph("Relevé de compte", font(11f, color = muted)))
        left.addElement(Paragraph(monthName() + if (s.partial) " (en cours)" else "", font(14f, bold = true)).apply { spacingBefore = 8f })
        left.addElement(
            Paragraph(
                "Du ${long(s.from)} au ${long(s.to)}" + if (s.partial) ", arrêté à ce jour" else "",
                font(9.5f, color = muted),
            ),
        )
        val right = PdfPCell().apply { border = Rectangle.NO_BORDER; horizontalAlignment = Element.ALIGN_RIGHT }
        right.addElement(Paragraph("Titulaire", font(8.5f, color = muted)).apply { alignment = Element.ALIGN_RIGHT })
        right.addElement(Paragraph(s.holderName, font(11f, bold = true)).apply { alignment = Element.ALIGN_RIGHT })
        right.addElement(Paragraph(s.holderPhone, font(10f)).apply { alignment = Element.ALIGN_RIGHT })
        right.addElement(Paragraph("Compte en dollars américains (USD)", font(8.5f, color = muted)).apply { alignment = Element.ALIGN_RIGHT; spacingBefore = 6f })
        t.addCell(left)
        t.addCell(right)
        return t
    }

    private fun summary(): PdfPTable {
        val t = PdfPTable(4).apply { widthPercentage = 100f }
        fun box(label: String, value: String, strong: Boolean = false) = PdfPCell().apply {
            backgroundColor = tint
            borderColor = Color.WHITE
            borderWidth = 2f
            setPadding(8f)
            addElement(Paragraph(label, font(8.5f, color = muted)))
            addElement(Paragraph(value, font(if (strong) 13f else 12f, bold = true)))
        }
        t.addCell(box("Solde au ${long(s.from)}", money(s.openingBalanceMinor)))
        t.addCell(box("Entrées", "+${money(s.totalInMinor)}"))
        t.addCell(box("Sorties" + if (s.feesMinor > 0) ", dont ${money(s.feesMinor)} de frais" else "", money(-s.totalOutMinor)))
        t.addCell(box("Solde au ${long(s.to)}", money(s.closingBalanceMinor), strong = true))
        return t
    }

    private fun operations(): PdfPTable {
        val t = PdfPTable(floatArrayOf(0.8f, 3.4f, 1.9f, 1.3f, 1.3f)).apply {
            widthPercentage = 100f
            headerRows = 1
        }
        fun head(text: String, right: Boolean = false) = PdfPCell(Phrase(text, font(8.5f, bold = true, color = muted))).apply {
            border = Rectangle.BOTTOM
            borderColor = ink
            paddingBottom = 6f
            if (right) horizontalAlignment = Element.ALIGN_RIGHT
        }
        listOf("Date", "Opération", "N° de reçu").forEach { t.addCell(head(it)) }
        t.addCell(head("Montant", right = true))
        t.addCell(head("Solde", right = true))

        var balance = s.openingBalanceMinor
        for (i in s.items) {
            balance += i.netMinor
            val at = i.createdAt.atZoneSameInstant(zone)
            fun cell(p: Phrase, right: Boolean = false) = PdfPCell(p).apply {
                border = Rectangle.BOTTOM
                borderColor = line
                paddingTop = 6f
                paddingBottom = 7f
                if (right) horizontalAlignment = Element.ALIGN_RIGHT
            }
            t.addCell(cell(Phrase().apply {
                add(Chunk(at.format(dayShort), font(9f)))
                add(Chunk("\n" + at.format(time), font(8f, color = muted)))
            }))
            t.addCell(cell(Phrase().apply {
                add(Chunk(title(i), font(9.5f, bold = true)))
                detail(i)?.let { add(Chunk("\n$it", font(8f, color = muted))) }
            }))
            t.addCell(cell(Phrase(i.receiptNumber, font(8.5f, color = muted))))
            t.addCell(cell(Phrase(signed(i.netMinor), font(9.5f, bold = true, color = if (i.netMinor > 0) green else ink)), right = true))
            t.addCell(cell(Phrase(money(balance), font(9f, color = muted)), right = true))
        }
        return t
    }

    private fun title(i: HistoryItem): String {
        val op = i.operator?.let(::operatorLabel)
        return when (i.kind) {
            HistoryKind.TOPUP -> op?.let { "Recharge $it" } ?: "Recharge"
            HistoryKind.TRANSFER_SENT -> "Envoi à ${i.counterpartyName ?: "un client"}"
            HistoryKind.TRANSFER_RECEIVED -> "Reçu de ${i.counterpartyName ?: "un client"}"
            HistoryKind.WITHDRAWAL -> "Retrait vers ${op ?: "mobile money"}"
            HistoryKind.WITHDRAWAL_REFUND -> "Retrait remboursé"
            HistoryKind.TRANSFER_REFUND -> "Envoi non réclamé par ${i.counterpartyName ?: "le proche"}, remboursé"
            HistoryKind.MERCHANT_PAYMENT -> "Paiement à ${i.counterpartyName ?: "un marchand"}"
            HistoryKind.AIRTIME -> "Crédit ${networkLabel(i.network)}"
            HistoryKind.AIRTIME_REFUND -> "Crédit non envoyé, remboursé"
            HistoryKind.BILL_PAYMENT -> "Facture ${i.counterpartyName ?: ""} ${i.billReference ?: ""}".trimEnd()
            HistoryKind.BILL_REFUND -> "Facture non payée, remboursée"
            HistoryKind.SAVINGS_DEPOSIT -> "Mis de côté (épargne)"
            HistoryKind.SAVINGS_WITHDRAWAL -> "Repris de l'épargne"
            HistoryKind.POT_CONTRIBUTION -> "Participation à la cagnotte « ${i.counterpartyName ?: ""} »"
            HistoryKind.POT_PAYOUT -> "Cagnotte « ${i.counterpartyName ?: ""} » versée"
            HistoryKind.CARD_PAYMENT -> "Carte : ${i.counterpartyName ?: "commerçant"}"
            HistoryKind.CARD_REFUND -> "Carte : paiement annulé par ${i.counterpartyName ?: "le commerçant"}"
            HistoryKind.CARD_FEE -> "Frais de carte"
            HistoryKind.EXCHANGE -> i.exchange?.let { "Change ${it.from} vers ${it.to}" } ?: "Change"
            HistoryKind.ADJUSTMENT -> "Correction de solde"
        }
    }

    private fun networkLabel(n: String?) = n?.let { cd.capitalinvest.platform.payments.airtime.MobileNetwork.valueOf(it).label } ?: "téléphone"

    private fun detail(i: HistoryItem): String? = listOfNotNull(
        i.counterpartyPhone ?: i.msisdn,
        i.note?.let { "« $it »" },
        if (i.feeMinor > 0) "dont ${money(i.feeMinor)} de frais" else null,
        when (i.status) {
            HistoryStatus.PENDING -> "en cours"
            HistoryStatus.REVIEW -> "en vérification"
            HistoryStatus.FAILED -> "refusé, remboursé"
            HistoryStatus.COMPLETED -> null
        },
    ).joinToString(" · ").ifEmpty { null }

    private inner class Footer : PdfPageEventHelper() {
        override fun onEndPage(writer: PdfWriter, document: Document) {
            val cb = writer.directContent
            val text = "Capital Invest · relevé émis le ${s.issuedAt.atZoneSameInstant(zone).format(stamp)}. " +
                "Une opération à contester ? Appelez le service client avec son n° de reçu."
            val f = font(7.5f, color = muted)
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, Phrase(text, f), document.left(), 30f, 0f)
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, Phrase("Page ${writer.pageNumber}", f), document.right(), 30f, 0f)
        }
    }
}

@RestController
@RequestMapping("/v1/me/statements")
class StatementController(private val statements: StatementService) {

    @GetMapping
    fun months(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = statements.months(customerId)

    @GetMapping("/{month}")
    fun statement(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable month: String) =
        statements.statement(customerId, parse(month))

    @GetMapping("/{month}/pdf")
    fun pdf(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable month: String): ResponseEntity<ByteArray> {
        val m = parse(month)
        val bytes = statements.pdf(statements.statement(customerId, m))
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("releve-capital-invest-$m.pdf").build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(bytes)
    }

    private fun parse(month: String): YearMonth = try {
        YearMonth.parse(month)
    } catch (e: DateTimeParseException) {
        throw BusinessException("INVALID_MONTH", "Mois attendu au format AAAA-MM")
    }
}
