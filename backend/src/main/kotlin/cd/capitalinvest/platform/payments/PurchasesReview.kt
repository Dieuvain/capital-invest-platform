package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.payments.airtime.AirtimeProperties
import cd.capitalinvest.platform.payments.airtime.AirtimePurchase
import cd.capitalinvest.platform.payments.airtime.AirtimeService
import cd.capitalinvest.platform.payments.airtime.MobileNetwork
import cd.capitalinvest.platform.payments.bills.BillKind
import cd.capitalinvest.platform.payments.bills.BillPayment
import cd.capitalinvest.platform.payments.bills.BillService
import cd.capitalinvest.platform.payments.bills.Biller
import cd.capitalinvest.platform.payments.bills.BillsProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** Relance chaque minute les achats de crédit et les factures restés sans réponse sûre. */
@Component
class PurchaseJobs(
    private val airtime: AirtimeService,
    private val bills: BillService,
    private val airtimeProps: AirtimeProperties,
    private val billsProps: BillsProperties,
) {
    @Scheduled(fixedDelayString = "\${capital-invest.purchases.poll-delay-ms:60000}", initialDelay = 30_000)
    fun poll() {
        if (airtimeProps.pollerEnabled) airtime.checkDue()
        if (billsProps.pollerEnabled) bills.checkDue()
    }
}

enum class PurchaseType { AIRTIME, BILL }

/** Achat sans réponse sûre, tel que le back-office le montre. */
data class PendingPurchase(
    val type: PurchaseType,
    val id: UUID,
    val customerName: String,
    val customerPhone: String,
    /** « Crédit Vodacom » ou « SNEL prépayé ». */
    val label: String,
    /** Numéro rechargé, ou référence chez le facturier. */
    val target: String,
    /** Le titulaire chez le facturier (factures seulement). */
    val holderName: String?,
    /** Compteur prépayé : « payé » demande le code du compteur. */
    val needsToken: Boolean,
    val amountMinor: Long,
    val provider: String,
    /** PENDING (relances en cours) ou REVIEW (24 h sans réponse sûre). */
    val status: String,
    val reason: String?,
    val checks: Int,
    val createdAt: OffsetDateTime,
)

enum class PurchaseDecision { DONE, REFUND }

data class PurchaseResolveRequest(
    val action: PurchaseDecision,
    @field:NotBlank @field:Size(max = 300) val note: String,
    /** Compteur prépayé : le code donné par l'agrégateur. */
    val token: String? = null,
)

/** Écran « Crédit et factures sans réponse » du back-office. Conformité et administrateurs. */
@RestController
@RequestMapping("/v1/backoffice")
class PurchasesReviewController(
    private val jdbc: JdbcClient,
    private val airtime: AirtimeService,
    private val bills: BillService,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private fun requireAccess(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    /** Les achats à 24 h sans réponse, et ceux qui attendent depuis plus de 10 minutes (pour un client qui appelle). */
    @GetMapping("/purchases/review")
    fun review(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal): List<PendingPurchase> {
        requireAccess(me)
        val since = OffsetDateTime.now(clock).minus(Duration.ofMinutes(10))
        val where = "(p.status = 'REVIEW' OR (p.status = 'PENDING' AND p.created_at < :since))"
        val airtimeRows = jdbc.sql(
            """SELECT p.*, c.full_name, c.phone FROM airtime_purchase p JOIN customer c ON c.id = p.customer_id WHERE $where""",
        ).param("since", since).query { rs, _ ->
            PendingPurchase(
                PurchaseType.AIRTIME, rs.getObject("id", UUID::class.java), rs.getString("full_name"), rs.getString("phone"),
                "Crédit ${MobileNetwork.valueOf(rs.getString("network")).label}", "+" + rs.getString("msisdn"), null, false,
                rs.getLong("amount_minor"), rs.getString("provider"), rs.getString("status"), rs.getString("review_reason"),
                rs.getInt("checks"), rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list()
        val billRows = jdbc.sql(
            """SELECT p.*, c.full_name, c.phone FROM bill_payment p JOIN customer c ON c.id = p.customer_id WHERE $where""",
        ).param("since", since).query { rs, _ ->
            val b = Biller.valueOf(rs.getString("biller"))
            PendingPurchase(
                PurchaseType.BILL, rs.getObject("id", UUID::class.java), rs.getString("full_name"), rs.getString("phone"),
                b.label, rs.getString("reference"), rs.getString("holder_name"), b.kind == BillKind.PREPAID,
                rs.getLong("amount_minor"), rs.getString("provider"), rs.getString("status"), rs.getString("review_reason"),
                rs.getInt("checks"), rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list()
        // Les plus anciens d'abord : ce sont les clients qui attendent depuis le plus longtemps.
        return (airtimeRows + billRows).sortedWith(compareByDescending<PendingPurchase> { it.status == "REVIEW" }.thenBy { it.createdAt })
    }

    @PostMapping("/airtime/{id}/resolve")
    fun resolveAirtime(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody body: PurchaseResolveRequest,
    ): AirtimePurchase {
        requireAccess(me)
        val p = airtime.resolve(id, body.action == PurchaseDecision.DONE, body.note.trim())
        audit.record(ActorType.AGENT, me.id, "AIRTIME_REVIEW_${body.action}", "AIRTIME", id, "${p.amountMinor} ${p.currency} ${p.phone} : ${body.note.trim()}")
        return p
    }

    @PostMapping("/bills/{id}/resolve")
    fun resolveBill(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody body: PurchaseResolveRequest,
    ): BillPayment {
        requireAccess(me)
        val p = bills.resolve(id, body.action == PurchaseDecision.DONE, body.note.trim(), body.token)
        audit.record(ActorType.AGENT, me.id, "BILL_REVIEW_${body.action}", "BILL", id, "${p.amountMinor} ${p.currency} ${p.billerName} ${p.reference} : ${body.note.trim()}")
        return p
    }
}
