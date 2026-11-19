package cd.capitalinvest.platform.monitoring

import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.merchants.MerchantService
import cd.capitalinvest.platform.merchants.MerchantStatus
import cd.capitalinvest.platform.notifications.MoneyMoved
import cd.capitalinvest.platform.notifications.NotificationService.Companion.money
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Seuils des règles de surveillance. PROVISOIRES : à valider par la conformité (et à revoir avec les
 * premiers mois d'activité réelle, pour ne pas noyer les agents sous les fausses alertes).
 */
@ConfigurationProperties("capital-invest.monitoring")
data class MonitoringProperties(
    val enabled: Boolean = true,
    /** Rafale : autant de sorties d'argent (envois, paiements, retraits) en une heure. */
    val velocityCount: Int = 10,
    val velocityWindowMinutes: Long = 60,
    /** Fractionnement : autant d'opérations en 24 h, chacune à au moins ce pourcentage du plafond par opération. */
    val structuringCount: Int = 3,
    val structuringPercentOfLimit: Int = 90,
    /** Compte collecteur : argent reçu d'autant de clients différents en 24 h. */
    val fanInSenders: Int = 5,
    /** Entrée-sortie rapide : retirer au moins ce pourcentage de ce qui est entré en 24 h… */
    val cashOutPercent: Int = 80,
    /** … quand ce qui est entré atteint au moins ce montant (500,00 $). */
    val cashOutMinMinor: Long = 50_000,
    /** Marchand : un même client qui paie autant de fois en 24 h (faux achats pour sortir de l'argent). */
    val merchantRepeatPayments: Int = 5,
    /** Marchand : encaissements des dernières 24 h à au moins ce multiple de la moyenne quotidienne des 30 jours d'avant… */
    val merchantSpikeFactor: Int = 3,
    /** … et à partir de ce montant (1 000,00 $), pour ne pas alerter sur les petits commerces. */
    val merchantSpikeMinMinor: Long = 100_000,
)

enum class MonitoringRule(val label: String) {
    VELOCITY("Rafale d'opérations"),
    STRUCTURING("Montants juste sous le plafond"),
    FAN_IN("Argent reçu de nombreux clients"),
    RAPID_CASH_OUT("Retrait aussitôt l'argent reçu"),
    MERCHANT_REPEAT_PAYER("Un client paie le marchand encore et encore"),
    MERCHANT_SPIKE("Encaissements anormalement élevés"),
    MERCHANT_SELF_PAYMENT("Le marchand se paie lui-même"),
}

enum class AlertStatus { OPEN, DISMISSED, ESCALATED }

data class MonitoringAlert(
    val id: UUID,
    val rule: MonitoringRule,
    val ruleLabel: String,
    val detail: String,
    val transactionId: UUID,
    val status: AlertStatus,
    val createdAt: OffsetDateTime,
    /** Client surveillé, ou null pour une alerte sur un marchand. */
    val customerId: UUID?,
    val customerName: String?,
    val customerPhone: String?,
    val customerStatus: String?,
    val kycLevel: Int?,
    /** Marchand surveillé, ou null pour une alerte sur un client. */
    val merchantId: UUID? = null,
    val merchantName: String? = null,
    val merchantCode: String? = null,
    val merchantStatus: String? = null,
    val resolvedByName: String?,
    val resolvedAt: OffsetDateTime?,
    val resolutionNote: String?,
)

/**
 * Après chaque mouvement d'argent, regarde le comportement récent de chaque client concerné.
 * Une règle franchie ouvre une alerte pour la conformité ; rien n'est bloqué automatiquement
 * et le client n'est pas prévenu (il ne doit pas savoir qu'il est surveillé).
 */
@Service
class MonitoringService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val limits: LimitsService,
    private val merchants: MerchantService,
    private val props: MonitoringProperties,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private data class Movement(val transactionId: UUID, val type: String, val amountMinor: Long, val at: OffsetDateTime, val counterpart: UUID?)

    /** Contrôle les clients touchés par cette opération. Renvoie les alertes ouvertes. */
    fun check(transactionId: UUID): List<MonitoringRule> {
        if (!props.enabled) return emptyList()
        val touched = jdbc.sql(
            """SELECT a.id AS wallet, a.customer_id, e.direction, t.type, t.created_at FROM ledger_entry e
               JOIN ledger_account a ON a.id = e.account_id JOIN ledger_transaction t ON t.id = e.transaction_id
               WHERE e.transaction_id = :tx AND a.type = 'CUSTOMER_WALLET'""",
        ).param("tx", transactionId).query { rs, _ ->
            listOf(
                rs.getObject("wallet", UUID::class.java), rs.getObject("customer_id", UUID::class.java), rs.getString("direction"),
                rs.getString("type"), rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list().distinctBy { it[1] to it[2] }
        val opened = mutableListOf<MonitoringRule>()
        for ((wallet, customerId, direction, type, at) in touched) {
            wallet as UUID; customerId as UUID; type as String; at as OffsetDateTime
            val customer = customers.findById(customerId) ?: continue
            val hits = mutableListOf<Pair<MonitoringRule, String>>()
            if (direction == "DEBIT" && type in OUTGOING) {
                val out = outgoing(wallet, at.minusHours(24), at)
                val burst = out.filter { it.at > at.minusMinutes(props.velocityWindowMinutes) }
                if (burst.size >= props.velocityCount) {
                    hits += MonitoringRule.VELOCITY to
                        "${burst.size} sorties d'argent en ${props.velocityWindowMinutes} min, pour ${money(burst.sumOf { it.amountMinor })}"
                }
                val limit = limits.tier(customer).perTransactionMinor
                val nearLimit = out.filter { it.amountMinor * 100 >= limit * props.structuringPercentOfLimit && it.amountMinor <= limit }
                if (nearLimit.size >= props.structuringCount) {
                    hits += MonitoringRule.STRUCTURING to
                        "${nearLimit.size} opérations en 24 h entre ${money(limit * props.structuringPercentOfLimit / 100)} et ${money(limit)} " +
                        "(plafond par opération du niveau ${customer.kycLevel})"
                }
                if (type == "WITHDRAWAL") {
                    val incoming = incoming(wallet, at.minusHours(24), at).sumOf { it.amountMinor }
                    val withdrawn = out.filter { it.type == "WITHDRAWAL" }.sumOf { it.amountMinor }
                    if (incoming >= props.cashOutMinMinor && withdrawn * 100 >= incoming * props.cashOutPercent) {
                        hits += MonitoringRule.RAPID_CASH_OUT to "${money(withdrawn)} retirés en 24 h sur ${money(incoming)} reçus ou rechargés"
                    }
                }
            }
            if (direction == "CREDIT" && type == "TRANSFER") {
                val senders = incoming(wallet, at.minusHours(24), at).filter { it.type == "TRANSFER" }
                val distinct = senders.mapNotNull { it.counterpart }.toSet()
                if (distinct.size >= props.fanInSenders) {
                    hits += MonitoringRule.FAN_IN to "${distinct.size} expéditeurs différents en 24 h, pour ${money(senders.sumOf { it.amountMinor })}"
                }
            }
            for ((rule, detail) in hits) if (open(customerId, null, rule, detail, transactionId)) opened += rule
        }
        if (touched.any { it[3] == "MERCHANT_PAYMENT" }) opened += checkMerchant(transactionId)
        return opened
    }

    /**
     * Un marchand peut servir à blanchir : faux achats répétés d'un même client (l'argent ressort par le
     * reversement), chiffre d'affaires qui s'envole, ou marchand qui se paie depuis son propre numéro.
     */
    private fun checkMerchant(transactionId: UUID): List<MonitoringRule> {
        val p = jdbc.sql(
            """SELECT p.merchant_id, p.customer_id, p.created_at, m.phone AS merchant_phone, c.phone AS payer_phone, c.full_name
               FROM merchant_payment p JOIN merchant m ON m.id = p.merchant_id JOIN customer c ON c.id = p.customer_id
               WHERE p.transaction_id = :tx""",
        ).param("tx", transactionId).query { rs, _ ->
            listOf(
                rs.getObject("merchant_id", UUID::class.java), rs.getObject("customer_id", UUID::class.java),
                rs.getObject("created_at", OffsetDateTime::class.java), rs.getString("merchant_phone"), rs.getString("payer_phone"),
                rs.getString("full_name"),
            )
        }.optional().orElse(null) ?: return emptyList()
        val merchantId = p[0] as UUID
        val payerId = p[1] as UUID
        val at = p[2] as OffsetDateTime
        val hits = mutableListOf<Pair<MonitoringRule, String>>()

        if (p[3] == p[4]) {
            hits += MonitoringRule.MERCHANT_SELF_PAYMENT to "Paiement de ${p[5]} depuis ${p[4]}, le numéro du marchand lui-même"
        }
        val (count, total) = jdbc.sql(
            """SELECT COUNT(*) AS n, COALESCE(SUM(amount_minor), 0) AS total FROM merchant_payment
               WHERE merchant_id = :m AND customer_id = :c AND created_at > :from AND created_at <= :to""",
        ).param("m", merchantId).param("c", payerId).param("from", at.minusHours(24)).param("to", at)
            .query { rs, _ -> rs.getInt("n") to rs.getLong("total") }.single()
        if (count >= props.merchantRepeatPayments) {
            hits += MonitoringRule.MERCHANT_REPEAT_PAYER to "${p[5]} (${p[4]}) a payé $count fois en 24 h, pour ${money(total)}"
        }
        val (day, month) = jdbc.sql(
            """SELECT COALESCE(SUM(amount_minor) FILTER (WHERE created_at > :day), 0) AS day,
                      COALESCE(SUM(amount_minor) FILTER (WHERE created_at <= :day), 0) AS month
               FROM merchant_payment WHERE merchant_id = :m AND created_at > :month AND created_at <= :to""",
        ).param("m", merchantId).param("day", at.minusHours(24)).param("month", at.minusDays(31)).param("to", at)
            .query { rs, _ -> rs.getLong("day") to rs.getLong("month") }.single()
        val average = month / 30
        if (day >= props.merchantSpikeMinMinor && day >= average * props.merchantSpikeFactor) {
            hits += MonitoringRule.MERCHANT_SPIKE to
                "${money(day)} encaissés en 24 h, contre ${money(average)} par jour en moyenne sur les 30 jours d'avant"
        }
        return hits.filter { (rule, detail) -> open(null, merchantId, rule, detail, transactionId) }.map { it.first }
    }

    private fun outgoing(wallet: UUID, from: OffsetDateTime, to: OffsetDateTime): List<Movement> = jdbc.sql(
        // Le montant de l'opération est la plus grosse ligne au débit : les frais sont une ligne à part.
        """SELECT t.id, t.type, t.created_at, MAX(e.amount_minor) AS amount FROM ledger_entry e
           JOIN ledger_transaction t ON t.id = e.transaction_id
           WHERE e.account_id = :w AND e.direction = 'DEBIT' AND t.type IN ('TRANSFER', 'MERCHANT_PAYMENT', 'WITHDRAWAL')
             AND t.created_at > :from AND t.created_at <= :to
           GROUP BY t.id, t.type, t.created_at""",
    ).param("w", wallet).param("from", from).param("to", to).query { rs, _ ->
        Movement(rs.getObject("id", UUID::class.java), rs.getString("type"), rs.getLong("amount"), rs.getObject("created_at", OffsetDateTime::class.java), null)
    }.list()

    private fun incoming(wallet: UUID, from: OffsetDateTime, to: OffsetDateTime): List<Movement> = jdbc.sql(
        """SELECT t.id, t.type, t.created_at, e.amount_minor,
                  (SELECT a2.customer_id FROM ledger_entry e2 JOIN ledger_account a2 ON a2.id = e2.account_id
                   WHERE e2.transaction_id = t.id AND e2.direction = 'DEBIT' AND a2.type = 'CUSTOMER_WALLET' LIMIT 1) AS sender
           FROM ledger_entry e JOIN ledger_transaction t ON t.id = e.transaction_id
           WHERE e.account_id = :w AND e.direction = 'CREDIT' AND t.type IN ('TOPUP', 'TRANSFER')
             AND t.created_at > :from AND t.created_at <= :to""",
    ).param("w", wallet).param("from", from).param("to", to).query { rs, _ ->
        Movement(
            rs.getObject("id", UUID::class.java), rs.getString("type"), rs.getLong("amount_minor"),
            rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("sender", UUID::class.java),
        )
    }.list()

    /** Faux si une alerte de cette règle est déjà ouverte pour ce client ou ce marchand. */
    private fun open(customerId: UUID?, merchantId: UUID?, rule: MonitoringRule, detail: String, transactionId: UUID): Boolean {
        val id = UUID.randomUUID()
        val inserted = jdbc.sql(
            """INSERT INTO monitoring_alert (id, customer_id, merchant_id, rule, detail, transaction_id, created_at)
               VALUES (:id, :c, :m, :rule, :detail, :tx, :now) ON CONFLICT DO NOTHING""",
        ).param("id", id).param("c", customerId).param("m", merchantId).param("rule", rule.name).param("detail", detail)
            .param("tx", transactionId).param("now", now()).update()
        if (inserted == 0) return false
        audit.record(
            ActorType.SYSTEM, null, "MONITORING_ALERT_OPENED", if (merchantId != null) "MERCHANT" else "CUSTOMER",
            (merchantId ?: customerId)!!, "${rule.label} : $detail",
        )
        return true
    }

    fun list(status: String?, customerId: UUID?, limit: Int = 100, merchantId: UUID? = null): List<MonitoringAlert> {
        val where = buildList {
            when (status) {
                "OPEN" -> add("al.status = 'OPEN'")
                "CLOSED" -> add("al.status <> 'OPEN'")
            }
            if (customerId != null) add("al.customer_id = :c")
            if (merchantId != null) add("al.merchant_id = :m")
        }.ifEmpty { listOf("TRUE") }.joinToString(" AND ")
        var q = jdbc.sql(
            """SELECT al.*, c.full_name, c.phone, c.status AS customer_status, c.kyc_level, ag.full_name AS resolver,
                      m.name AS merchant_name, m.code AS merchant_code, m.status AS merchant_status
               FROM monitoring_alert al LEFT JOIN customer c ON c.id = al.customer_id
               LEFT JOIN merchant m ON m.id = al.merchant_id
               LEFT JOIN backoffice_agent ag ON ag.id = al.resolved_by
               WHERE $where ORDER BY al.created_at DESC, al.id DESC LIMIT :limit""",
        ).param("limit", limit.coerceIn(1, 500))
        if (customerId != null) q = q.param("c", customerId)
        if (merchantId != null) q = q.param("m", merchantId)
        return q.query { rs, _ ->
            val rule = MonitoringRule.valueOf(rs.getString("rule"))
            MonitoringAlert(
                rs.getObject("id", UUID::class.java), rule, rule.label, rs.getString("detail"),
                rs.getObject("transaction_id", UUID::class.java), AlertStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("customer_id", UUID::class.java),
                rs.getString("full_name"), rs.getString("phone"), rs.getString("customer_status"), rs.getObject("kyc_level") as Int?,
                rs.getObject("merchant_id", UUID::class.java), rs.getString("merchant_name"), rs.getString("merchant_code"),
                rs.getString("merchant_status"),
                rs.getString("resolver"), rs.getObject("resolved_at", OffsetDateTime::class.java), rs.getString("resolution_note"),
            )
        }.list()
    }

    fun get(id: UUID): MonitoringAlert =
        jdbc.sql("SELECT customer_id, merchant_id FROM monitoring_alert WHERE id = :id").param("id", id)
            .query { rs, _ -> rs.getObject("customer_id", UUID::class.java) to rs.getObject("merchant_id", UUID::class.java) }
            .optional().orElse(null)
            ?.let { (c, m) -> list(null, c, 500, m).first { it.id == id } }
            ?: throw NotFoundException("ALERT_NOT_FOUND", "Alerte introuvable")

    /** Classe l'alerte sans suite, ou suspend le compte du client. La note explique la décision (contrôle de la BCC). */
    @Transactional
    fun resolve(agentId: UUID, id: UUID, action: ResolveAction, note: String): MonitoringAlert {
        val text = note.trim()
        if (text.length < 5) throw BusinessException("NOTE_REQUIRED", "Expliquez la décision en quelques mots")
        val updated = jdbc.sql(
            """UPDATE monitoring_alert SET status = :s, resolved_by = :by, resolved_at = :now, resolution_note = :note
               WHERE id = :id AND status = 'OPEN'""",
        ).param("s", if (action == ResolveAction.SUSPEND) "ESCALATED" else "DISMISSED").param("by", agentId)
            .param("now", now()).param("note", text).param("id", id).update()
        val alert = get(id)
        if (updated == 0) throw BusinessException("ALERT_ALREADY_RESOLVED", "Cette alerte a déjà été traitée")
        audit.record(
            ActorType.AGENT, agentId, if (action == ResolveAction.SUSPEND) "MONITORING_ALERT_ESCALATED" else "MONITORING_ALERT_DISMISSED",
            if (alert.merchantId != null) "MERCHANT" else "CUSTOMER", (alert.merchantId ?: alert.customerId)!!, "${alert.ruleLabel} : $text",
        )
        if (action == ResolveAction.SUSPEND) {
            // Un marchand suspendu n'accepte plus de paiement, ni de reversement automatique.
            if (alert.merchantId != null) merchants.setStatus(agentId, alert.merchantId, MerchantStatus.SUSPENDED, text)
            else setCustomerStatus(agentId, alert.customerId!!, "SUSPENDED", text)
        }
        return get(id)
    }

    /** Suspend ou réactive un client. Un client suspendu ne peut plus se connecter ni faire bouger son argent. */
    @Transactional
    fun setCustomerStatus(agentId: UUID, customerId: UUID, status: String, reason: String): String {
        if (status !in listOf("ACTIVE", "SUSPENDED")) throw BusinessException("INVALID_STATUS", "État invalide")
        val c = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        if (c.status == "CLOSED") throw BusinessException("CUSTOMER_CLOSED", "Ce compte est fermé")
        if (reason.trim().length < 5) throw BusinessException("NOTE_REQUIRED", "Expliquez la décision en quelques mots")
        if (c.status != status) {
            jdbc.sql("UPDATE customer SET status = :s WHERE id = :id").param("s", status).param("id", customerId).update()
            // Les sessions ouvertes tombent : le client devra se reconnecter, ce qu'un compte suspendu ne peut pas faire.
            if (status == "SUSPENDED") {
                jdbc.sql("UPDATE session SET revoked_at = :now WHERE customer_id = :id AND revoked_at IS NULL")
                    .param("now", now()).param("id", customerId).update()
            }
            audit.record(
                ActorType.AGENT, agentId, if (status == "SUSPENDED") "CUSTOMER_SUSPENDED" else "CUSTOMER_REACTIVATED",
                "CUSTOMER", customerId, reason.trim(),
            )
        }
        return status
    }

    companion object {
        val OUTGOING = setOf("TRANSFER", "MERCHANT_PAYMENT", "WITHDRAWAL")
    }
}

/** Contrôle après la fin de l'opération, dans sa propre transaction : une erreur ici ne bloque jamais l'argent. */
@Component
class MonitoringListener(private val monitoring: MonitoringService, transactions: PlatformTransactionManager) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun afterCommit(event: MoneyMoved) {
        runCatching { tx.execute { monitoring.check(event.transactionId) } }
            .onFailure { log.error("Surveillance de l'opération {} : {}", event.transactionId, it.toString()) }
    }
}

enum class ResolveAction { DISMISS, SUSPEND }

data class ResolveAlertRequest(val action: ResolveAction, @field:Size(max = 500) val note: String)

data class CustomerStatusRequest(val status: String, @field:Size(max = 500) val reason: String)

data class CustomerStatusView(val customerId: UUID, val status: String)

/** Alertes de surveillance : réservées à la conformité et aux administrateurs. */
@RestController
@RequestMapping("/v1/backoffice")
class MonitoringController(private val monitoring: MonitoringService) {

    private fun requireCompliance(me: AgentPrincipal) {
        if (me.role != AgentRole.ADMIN && me.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("COMPLIANCE_ONLY", "Réservé à la conformité et aux administrateurs")
        }
    }

    /** status : OPEN (par défaut), CLOSED ou ALL. */
    @GetMapping("/monitoring/alerts")
    fun alerts(
        @RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal,
        @RequestParam(defaultValue = "OPEN") status: String,
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) merchantId: UUID?,
    ): List<MonitoringAlert> {
        requireCompliance(me)
        return monitoring.list(status, customerId, merchantId = merchantId)
    }

    @PostMapping("/monitoring/alerts/{id}/resolve")
    fun resolve(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: ResolveAlertRequest): MonitoringAlert {
        requireCompliance(me)
        return monitoring.resolve(me.id, id, b.action, b.note)
    }

    @PostMapping("/customers/{id}/status")
    fun customerStatus(@RequestAttribute(AGENT_ATTRIBUTE) me: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: CustomerStatusRequest): CustomerStatusView {
        requireCompliance(me)
        return CustomerStatusView(id, monitoring.setCustomerStatus(me.id, id, b.status, b.reason))
    }
}
