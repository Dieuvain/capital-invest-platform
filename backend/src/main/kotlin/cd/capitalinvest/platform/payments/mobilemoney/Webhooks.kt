package cd.capitalinvest.platform.payments.mobilemoney

import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.payments.TopupService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Journal brut des notifications reçues, gardé comme preuve. */
@Repository
class MobileMoneyEventRepository(private val jdbc: JdbcClient) {
    fun record(provider: String, signatureValid: Boolean?, topupId: UUID?, body: String, outcome: String) {
        jdbc.sql(
            """INSERT INTO mobile_money_event (id, provider, signature_valid, topup_id, body, outcome)
               VALUES (:id, :provider, :valid, :topup, :body, :outcome)""",
        ).param("id", UUID.randomUUID()).param("provider", provider).param("valid", signatureValid)
            .param("topup", topupId).param("body", body.take(20_000)).param("outcome", outcome).update()
    }
}

/**
 * Notifications des prestataires (« callbacks »). Leur contenu n'est jamais cru : elles ne servent qu'à
 * déclencher tout de suite la relecture du statut chez le prestataire, par son API authentifiée.
 * Une notification fausse ne peut donc rien créditer ; une notification perdue est rattrapée par les relances.
 */
@RestController
@RequestMapping("/v1/webhooks/mobile-money")
class MobileMoneyWebhookController(
    private val router: MobileMoneyRouter,
    private val topups: TopupService,
    private val events: MobileMoneyEventRepository,
) {
    private val log = LoggerFactory.getLogger(MobileMoneyWebhookController::class.java)

    @PostMapping("/{provider}")
    fun receive(
        @PathVariable provider: String,
        request: HttpServletRequest,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Map<String, Any>> {
        val connector = runCatching { router.byName(provider) }.getOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("code" to "UNKNOWN_PROVIDER"))
        val bytes = body ?: ByteArray(0)
        val text = String(bytes, Charsets.UTF_8)
        val headers = request.headerNames.toList().associate { it.lowercase() to request.getHeader(it) }
        val notification = IncomingNotification(
            request.method, request.getHeader("host") ?: request.serverName, request.requestURI, headers, bytes,
        )
        val parsed = connector.parseNotification(notification)

        if (parsed.signatureValid == false || (parsed.signatureValid == null && connector.requiresSignature)) {
            events.record(connector.name, parsed.signatureValid, null, text, "REJECTED_SIGNATURE")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("code" to "INVALID_SIGNATURE"))
        }
        val topupId = parsed.topupId
        if (topupId == null) {
            events.record(connector.name, parsed.signatureValid, null, text, "MALFORMED")
            return ResponseEntity.badRequest().body(mapOf("code" to "MALFORMED"))
        }
        val topup = runCatching { topups.get(topupId) }.getOrNull()
        if (topup == null || topup.provider != connector.name) {
            // Réponse 200 : rien à apprendre pour qui essaie des identifiants, et pas de renvoi en boucle.
            events.record(connector.name, parsed.signatureValid, null, text, "UNKNOWN_TOPUP $topupId")
            return ResponseEntity.ok(mapOf("received" to true))
        }
        val outcome = try {
            "REFRESHED ${topups.refresh(topupId).status}"
        } catch (e: Exception) {
            log.warn("Notification {} : relecture impossible : {}", topupId, e.toString())
            "REFRESH_ERROR ${e.javaClass.simpleName}"
        }
        events.record(connector.name, parsed.signatureValid, topupId, text, outcome)
        return ResponseEntity.ok(mapOf("received" to true))
    }
}

/** Relances automatiques et rapprochement de la nuit. */
@Component
class MobileMoneyJobs(
    private val topups: TopupService,
    private val reconciliation: ReconciliationService,
    private val properties: MobileMoneyProperties,
    private val limits: LimitsProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(MobileMoneyJobs::class.java)

    @Scheduled(fixedDelayString = "\${capital-invest.mobile-money.poll-delay-ms:5000}", initialDelay = 15_000)
    fun poll() {
        if (properties.pollerEnabled) topups.checkDue()
    }

    /** Chaque nuit à 2 h (heure de Lubumbashi), on rapproche les recharges de la veille. */
    @Scheduled(cron = "\${capital-invest.mobile-money.reconciliation-cron:0 0 2 * * *}", zone = "Africa/Lubumbashi")
    fun nightly() {
        if (!properties.reconciliationEnabled) return
        val yesterday = LocalDate.now(clock.withZone(ZoneId.of(limits.timezone))).minusDays(1)
        runCatching { reconciliation.run(yesterday) }.onFailure { log.error("Rapprochement du {} : {}", yesterday, it.toString()) }
    }
}
