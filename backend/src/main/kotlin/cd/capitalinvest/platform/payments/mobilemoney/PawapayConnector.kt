package cd.capitalinvest.platform.payments.mobilemoney

import cd.capitalinvest.platform.payments.Operator
import cd.capitalinvest.platform.payments.Topup
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.net.http.HttpClient
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Agrégateur pawaPay (API v2) : M-Pesa, Airtel Money et Orange Money en RDC par un seul contrat.
 *
 * - Demande : POST /v2/deposits, avec l'identifiant de la recharge comme depositId (rejouable sans doublon).
 * - Statut : GET /v2/deposits/{id}. Seul NOT_FOUND permet de conclure qu'une demande n'existe pas.
 * - Notifications : signées (RFC 9421) avec les clés publiées sur /v2/public-key/http.
 */
@Component
class PawapayConnector(
    private val properties: MobileMoneyProperties,
    private val json: ObjectMapper,
    private val clock: Clock,
) : MobileMoneyConnector {
    companion object {
        const val NAME = "PAWAPAY"
        const val SETTLEMENT_CODE = "SETTLEMENT_PAWAPAY_USD"
        private val log = LoggerFactory.getLogger(PawapayConnector::class.java)
    }

    private val config get() = properties.pawapay

    private val http: RestClient by lazy {
        val timeout = Duration.ofSeconds(config.timeoutSeconds)
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
        factory.setReadTimeout(timeout)
        RestClient.builder()
            .baseUrl(config.baseUrl.trimEnd('/'))
            .requestFactory(factory)
            .defaultHeader("Authorization", "Bearer ${config.apiToken}")
            .build()
    }

    override val name = NAME

    override val requiresSignature get() = config.requireSignedCallbacks

    // L'argent des trois opérateurs arrive sur le même compte chez pawaPay.
    override fun settlementCode(operator: Operator) = SETTLEMENT_CODE

    override fun initiate(request: PaymentRequest): InitiateResult {
        val provider = config.providers[request.operator]
            ?: return InitiateResult.Rejected("PROVIDER_NOT_CONFIGURED", "Opérateur non configuré chez pawaPay")
        val body = mapOf(
            "depositId" to request.topupId.toString(),
            "amount" to BigDecimal.valueOf(request.amountMinor, 2).toPlainString(),
            "currency" to request.currency.name,
            "payer" to mapOf(
                "type" to "MMO",
                "accountDetails" to mapOf("phoneNumber" to request.msisdn, "provider" to provider),
            ),
            "customerMessage" to "Capital Invest",
        )
        val res = try {
            http.post().uri("/v2/deposits").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            // Une erreur 4xx décrit une demande refusée ; une 5xx ne garantit pas que rien n'a été créé.
            if (e.statusCode.is4xxClientError) {
                val reason = failure(parse(e.responseBodyAsByteArray))
                return InitiateResult.Rejected(reason.first ?: "HTTP_${e.statusCode.value()}", reason.second ?: "Demande refusée par pawaPay")
            }
            log.warn("pawaPay deposit {} : HTTP {}", request.topupId, e.statusCode.value())
            return InitiateResult.Unknown("HTTP ${e.statusCode.value()}")
        } catch (e: Exception) {
            log.warn("pawaPay deposit {} : {}", request.topupId, e.toString())
            return InitiateResult.Unknown(e.javaClass.simpleName)
        } ?: return InitiateResult.Unknown("Réponse vide")

        return when (res.path("status").asText()) {
            "ACCEPTED", "DUPLICATE_IGNORED" -> InitiateResult.Accepted(null)
            "REJECTED" -> failure(res).let { InitiateResult.Rejected(it.first ?: "REJECTED", it.second ?: "Demande refusée") }
            else -> InitiateResult.Unknown("Statut inattendu : ${res.path("status").asText()}")
        }
    }

    override fun check(topup: Topup): CheckResult {
        val res = try {
            http.get().uri("/v2/deposits/{id}", topup.id).retrieve().body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            return CheckResult.Uncertain("HTTP ${e.statusCode.value()}")
        } catch (e: Exception) {
            return CheckResult.Uncertain(e.javaClass.simpleName)
        } ?: return CheckResult.Uncertain("Réponse vide")

        if (res.path("status").asText() == "NOT_FOUND") return CheckResult.NotFound
        if (res.path("status").asText() != "FOUND") return CheckResult.Uncertain("Statut inattendu : ${res.path("status").asText()}")
        val data = res.path("data")
        return when (val s = data.path("status").asText()) {
            "COMPLETED" -> {
                val amount = runCatching { BigDecimal(data.path("amount").asText()).movePointRight(2).longValueExact() }.getOrNull()
                    ?: return CheckResult.Uncertain("Montant illisible : ${data.path("amount").asText()}")
                CheckResult.Completed(amount, data.path("currency").asText(), data.path("providerTransactionId").textValue())
            }
            "FAILED" -> failure(data).let { CheckResult.Failed(it.first ?: "FAILED", it.second ?: "Paiement non abouti") }
            "ACCEPTED", "PROCESSING", "IN_RECONCILIATION", "SUBMITTED", "ENQUEUED" -> CheckResult.Pending
            else -> CheckResult.Uncertain("Statut inattendu : $s")
        }
    }

    override fun parseNotification(notification: IncomingNotification): ParsedNotification {
        val signed = notification.headers.containsKey("signature")
        val valid = if (signed) verifier.verify(notification, config.callbackAuthority?.takeIf { it.isNotBlank() }).also {
            if (!it.valid) log.warn("Notification pawaPay mal signée : {}", it.reason)
        }.valid else null
        val id = runCatching { UUID.fromString(parse(notification.body)?.path("depositId")?.asText()) }.getOrNull()
        return ParsedNotification(id, valid)
    }

    private fun parse(bytes: ByteArray): JsonNode? = runCatching { json.readTree(bytes) }.getOrNull()

    private fun failure(node: JsonNode?): Pair<String?, String?> {
        val f = node?.path("failureReason")
        return f?.path("failureCode")?.textValue() to f?.path("failureMessage")?.textValue()
    }

    // ---- Clés publiques des notifications ------------------------------------------------------

    private val keys = ConcurrentHashMap<String, PublicKey>()
    @Volatile private var keysFetchedAt: Instant = Instant.EPOCH

    private val verifier by lazy { HttpSignatureVerifier(clock, ::publicKey) }

    private fun publicKey(id: String): PublicKey? {
        keys[id]?.let { return it }
        // Clé inconnue : pawaPay a pu en publier une nouvelle. Une relecture par minute au plus.
        synchronized(this) {
            keys[id]?.let { return it }
            if (Duration.between(keysFetchedAt, clock.instant()) < Duration.ofMinutes(1)) return null
            keysFetchedAt = clock.instant()
            runCatching {
                http.get().uri("/v2/public-key/http").retrieve().body(JsonNode::class.java)?.forEach {
                    keys[it.path("id").asText()] = HttpSignatureVerifier.parsePublicKey(it.path("key").asText())
                }
            }.onFailure { log.warn("Clés publiques pawaPay illisibles : {}", it.toString()) }
        }
        return keys[id]
    }
}
