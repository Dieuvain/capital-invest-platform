package cd.capitalinvest.platform.cards

import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.notifications.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.cards")
data class CardsProperties(
    /** Processeur des nouvelles cartes : `simulator` tant qu'aucun partenaire n'est choisi. */
    val processor: String = SimulatedCardProcessor.NAME,
    /** Agences où retirer une carte physique. */
    val branches: List<String> = listOf("Kolwezi - Agence principale", "Lubumbashi", "Kalemie", "Kinshasa"),
    /** Prix de la première carte physique : 1 $ pour l'instant (décision du 21/11/2026). */
    val physicalCardFeeMinor: Long = 100,
    /** Remplacement d'une carte physique bloquée ou expirée (5 $, dossier d'analyse). */
    val replacementFeeMinor: Long = 500,
    /** Plafond journalier d'une nouvelle carte ; le client peut le changer jusqu'au plafond journalier de son niveau. */
    val defaultDailyLimitMinor: Long = 50_000,
)

enum class CardKind { VIRTUAL, PHYSICAL }

/** Diagramme d'états 4.1 de l'analyse UML. */
enum class CardStatus { ORDERED, IN_PRODUCTION, READY_FOR_PICKUP, ACTIVE, FROZEN, BLOCKED, EXPIRED, DELETED }

enum class CardBlockReason { LOST, STOLEN, FRAUD }

data class Card(
    val id: UUID,
    val kind: CardKind,
    val status: CardStatus,
    val network: CardNetwork,
    val last4: String,
    val expiryMonth: Int,
    val expiryYear: Int,
    val embossedName: String,
    val dailyLimitMinor: Long,
    /** Payé avec cette carte depuis minuit (heure de Lubumbashi). */
    val spentTodayMinor: Long,
    val onlinePayments: Boolean,
    val pickupBranch: String?,
    val blockReason: CardBlockReason?,
    /** Carte bloquée ou expirée qui peut être remplacée (physique : avec frais ; virtuelle : en créant une nouvelle). */
    val replaceable: Boolean,
    val createdAt: OffsetDateTime,
    val activatedAt: OffsetDateTime?,
    val blockedAt: OffsetDateTime?,
)

data class CardsOverview(
    /** KYC niveau 2 validé et compte actif : le client peut avoir des cartes. */
    val eligible: Boolean,
    val kycLevel: Int,
    val cards: List<Card>,
    val branches: List<String>,
    val physicalCardFeeMinor: Long,
    val replacementFeeMinor: Long,
    /** Plus haut plafond journalier qu'une carte peut avoir : celui du niveau du client. */
    val maxDailyLimitMinor: Long,
)

data class CardDetails(val cardId: UUID, val pan: String, val cvv: String, val expiryMonth: Int, val expiryYear: Int, val embossedName: String)

enum class AuthorizationStatus { APPROVED, DECLINED, REVERSED }

data class CardAuthorization(
    val id: UUID,
    val cardId: UUID,
    val amountMinor: Long,
    val currency: String,
    val merchantName: String,
    val merchantCity: String?,
    val online: Boolean,
    val status: AuthorizationStatus,
    val declineCode: String?,
    /** Explication du refus, à montrer au client. */
    val declineMessage: String?,
    /** Opération au grand livre, pour ouvrir le reçu. */
    val transactionId: UUID?,
    val createdAt: OffsetDateTime,
)

/** Demande d'autorisation du processeur, déjà traduite depuis son format (ISO 8583 côté réseau). */
data class AuthorizationRequest(
    val processorReference: String,
    val amountMinor: Long,
    val currency: String = "USD",
    val merchantName: String,
    val merchantCity: String? = null,
    val online: Boolean = false,
)

private val processorDown = { e: Exception ->
    BusinessException("CARD_PROCESSOR_UNAVAILABLE", "Le service des cartes ne répond pas. Réessayez dans un moment.", HttpStatus.SERVICE_UNAVAILABLE)
        .apply { initCause(e) }
}

/**
 * Cartes prépayées du client : virtuelle (émise tout de suite) et physique (commandée, fabriquée,
 * retirée en agence, activée avec le code du pli et un PIN). Les deux dépensent le portefeuille USD.
 *
 * Règles (dossier d'analyse et analyse UML) :
 * - il faut le KYC de niveau 2 ;
 * - une seule carte vivante de chaque sorte ;
 * - la carte gelée se dégèle depuis l'app ; la carte bloquée ne revient jamais : on la remplace,
 *   avec un nouveau numéro, et la carte physique repart de « commandée » ;
 * - le numéro complet n'est jamais stocké : il est demandé au processeur à chaque affichage.
 *
 * Les appels au processeur se font hors transaction : on ne garde pas un verrou pendant un appel réseau.
 */
@Service
class CardService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val limitsProps: LimitsProperties,
    private val processors: CardProcessorRouter,
    private val notifications: NotificationService,
    private val audit: AuditLog,
    private val props: CardsProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    companion object {
        const val SETTLEMENT = "CARD_SETTLEMENT_USD"
        const val FEES = "FEES_REVENUE_USD"
        val LIVE = setOf(CardStatus.ORDERED, CardStatus.IN_PRODUCTION, CardStatus.READY_FOR_PICKUP, CardStatus.ACTIVE, CardStatus.FROZEN)
    }

    private val log = LoggerFactory.getLogger(CardService::class.java)
    private val zone = ZoneId.of(limitsProps.timezone)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    internal class Row(
        val id: UUID, val customerId: UUID, val kind: CardKind, val status: CardStatus, val processor: String, val token: String,
        val network: CardNetwork, val last4: String, val expiryMonth: Int, val expiryYear: Int, val embossedName: String,
        val dailyLimitMinor: Long, val onlinePayments: Boolean, val pickupBranch: String?, val blockReason: CardBlockReason?,
        val replacesCardId: UUID?, val createdAt: OffsetDateTime, val activatedAt: OffsetDateTime?, val blockedAt: OffsetDateTime?,
    ) {
        /** Une carte n'est plus utilisable après le dernier jour de son mois d'expiration. */
        fun expired(today: java.time.LocalDate) = today.isAfter(YearMonth.of(expiryYear, expiryMonth).atEndOfMonth())
    }

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Row(
        rs.getObject("id", UUID::class.java), rs.getObject("customer_id", UUID::class.java), CardKind.valueOf(rs.getString("kind")),
        CardStatus.valueOf(rs.getString("status")), rs.getString("processor"), rs.getString("processor_token"),
        CardNetwork.valueOf(rs.getString("network")), rs.getString("last4"), rs.getInt("expiry_month"), rs.getInt("expiry_year"),
        rs.getString("embossed_name"), rs.getLong("daily_limit_minor"), rs.getBoolean("online_payments"), rs.getString("pickup_branch"),
        rs.getString("block_reason")?.let(CardBlockReason::valueOf), rs.getObject("replaces_card_id", UUID::class.java),
        rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("activated_at", OffsetDateTime::class.java),
        rs.getObject("blocked_at", OffsetDateTime::class.java),
    )

    internal fun row(id: UUID, lock: Boolean = false): Row? =
        jdbc.sql("SELECT * FROM card WHERE id = :id${if (lock) " FOR UPDATE" else ""}").param("id", id).query(::map).optional().orElse(null)

    private fun owned(customerId: UUID, id: UUID, lock: Boolean = false): Row =
        row(id, lock)?.takeIf { it.customerId == customerId && it.status != CardStatus.DELETED }
            ?: throw NotFoundException("CARD_NOT_FOUND", "Carte introuvable")

    private fun customer(id: UUID): Customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    private fun lockCustomer(id: UUID) {
        jdbc.sql("SELECT id FROM customer WHERE id = :id FOR UPDATE").param("id", id).query(UUID::class.java).single()
    }

    private fun requireEligible(c: Customer) {
        if (c.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu ou fermé")
        if (c.kycLevel < 2) {
            throw BusinessException("KYC_LEVEL_REQUIRED", "Les cartes demandent le niveau 2 : envoyez votre pièce d'identité depuis le Profil", HttpStatus.FORBIDDEN)
        }
    }

    private fun live(customerId: UUID, kind: CardKind): Row? =
        jdbc.sql("SELECT * FROM card WHERE customer_id = :c AND kind = :k AND status IN (:live)")
            .param("c", customerId).param("k", kind.name).param("live", LIVE.map { it.name })
            .query(::map).list().firstOrNull()

    /** Cartes dont le mois d'expiration est passé : elles sortent des cartes vivantes, pour être remplacées. */
    private fun markExpired(customerId: UUID) {
        val today = clock.instant().atZone(zone).toLocalDate()
        jdbc.sql(
            """UPDATE card SET status = 'EXPIRED', updated_at = :now
               WHERE customer_id = :c AND status IN ('ACTIVE', 'FROZEN')
                 AND (make_date(expiry_year, expiry_month, 1) + INTERVAL '1 month')::date <= :today""",
        ).param("now", now()).param("c", customerId).param("today", today).update()
    }

    private fun dayStart(): OffsetDateTime = clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime()

    private fun spentToday(cardId: UUID): Long =
        jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM card_authorization WHERE card_id = :c AND status = 'APPROVED' AND created_at >= :since")
            .param("c", cardId).param("since", dayStart()).query(Long::class.java).single()

    private fun maxDailyLimit(c: Customer) = limits.tier(c).dailyOutMinor

    private fun view(r: Row): Card {
        val today = clock.instant().atZone(zone).toLocalDate()
        val status = if (r.status in setOf(CardStatus.ACTIVE, CardStatus.FROZEN) && r.expired(today)) CardStatus.EXPIRED else r.status
        val replaced = jdbc.sql("SELECT count(*) FROM card WHERE replaces_card_id = :id").param("id", r.id).query(Int::class.java).single() > 0
        return Card(
            id = r.id, kind = r.kind, status = status, network = r.network, last4 = r.last4, expiryMonth = r.expiryMonth,
            expiryYear = r.expiryYear, embossedName = r.embossedName, dailyLimitMinor = r.dailyLimitMinor, spentTodayMinor = spentToday(r.id),
            onlinePayments = r.onlinePayments, pickupBranch = r.pickupBranch, blockReason = r.blockReason,
            replaceable = status in setOf(CardStatus.BLOCKED, CardStatus.EXPIRED) && !replaced &&
                live(r.customerId, r.kind).let { it == null || it.id == r.id },
            createdAt = r.createdAt, activatedAt = r.activatedAt, blockedAt = r.blockedAt,
        )
    }

    fun overview(customerId: UUID): CardsOverview {
        val c = customer(customerId)
        // Cartes vivantes, et cartes bloquées ou expirées pas encore remplacées (le client doit pouvoir les remplacer).
        val cards = jdbc.sql(
            """SELECT * FROM card k WHERE k.customer_id = :c AND k.status <> 'DELETED'
                 AND NOT EXISTS (SELECT 1 FROM card n WHERE n.replaces_card_id = k.id)
                 AND (k.status NOT IN ('BLOCKED', 'EXPIRED')
                      OR NOT EXISTS (SELECT 1 FROM card l WHERE l.customer_id = k.customer_id AND l.kind = k.kind AND l.created_at > k.created_at))
               ORDER BY (k.kind = 'VIRTUAL') DESC, k.created_at DESC""",
        ).param("c", customerId).query(::map).list().map(::view)
        return CardsOverview(
            eligible = c.status == "ACTIVE" && c.kycLevel >= 2, kycLevel = c.kycLevel, cards = cards, branches = props.branches,
            physicalCardFeeMinor = props.physicalCardFeeMinor, replacementFeeMinor = props.replacementFeeMinor,
            maxDailyLimitMinor = maxDailyLimit(c),
        )
    }

    fun get(customerId: UUID, cardId: UUID): Card = view(owned(customerId, cardId))

    /** Carte virtuelle : émise tout de suite, active, paiements en ligne permis (c'est son usage). */
    fun createVirtual(customerId: UUID): Card {
        val c = tx.execute {
            val c = customer(customerId)
            requireEligible(c)
            lockCustomer(customerId)
            markExpired(customerId)
            if (live(customerId, CardKind.VIRTUAL) != null) {
                throw ConflictException("CARD_ALREADY_EXISTS", "Vous avez déjà une carte virtuelle")
            }
            c
        }!!
        val limit = minOf(props.defaultDailyLimitMinor, maxDailyLimit(c))
        return issueAndSave(c, CardKind.VIRTUAL, limit, onlinePayments = true, branch = null, replaces = null, key = null, feeMinor = 0, feeLabel = null)
    }

    /** Commande de la carte physique : frais éventuels débités à la commande ; paiements en ligne coupés par défaut. */
    fun orderPhysical(customerId: UUID, idempotencyKey: String, branch: String): Card {
        replay(customerId, idempotencyKey)?.let { return it }
        val c = tx.execute {
            val c = customer(customerId)
            requireEligible(c)
            lockCustomer(customerId)
            markExpired(customerId)
            if (live(customerId, CardKind.PHYSICAL) != null) {
                throw ConflictException("CARD_ALREADY_EXISTS", "Vous avez déjà une carte physique, commandée ou en service")
            }
            c
        }!!
        val limit = minOf(props.defaultDailyLimitMinor, maxDailyLimit(c))
        return issueAndSave(
            c, CardKind.PHYSICAL, limit, onlinePayments = false, branch = checkBranch(branch), replaces = null, key = idempotencyKey,
            feeMinor = props.physicalCardFeeMinor, feeLabel = "Commande de la carte physique",
        )
    }

    /**
     * Remplacement d'une carte bloquée ou expirée. Physique : nouvelle carte, nouveau numéro, frais de remplacement,
     * elle repart de « commandée » ; mêmes plafond et réglages. Virtuelle : une nouvelle carte virtuelle, sans frais.
     */
    fun replace(customerId: UUID, cardId: UUID, idempotencyKey: String, branch: String?): Card {
        replay(customerId, idempotencyKey)?.let { return it }
        val (c, old) = tx.execute {
            val c = customer(customerId)
            requireEligible(c)
            lockCustomer(customerId)
            markExpired(customerId)
            val old = owned(customerId, cardId)
            if (view(old).status !in setOf(CardStatus.BLOCKED, CardStatus.EXPIRED)) {
                throw BusinessException("CARD_NOT_REPLACEABLE", "Seule une carte bloquée ou expirée se remplace. Gelez-la si vous l'avez seulement égarée.")
            }
            if (!view(old).replaceable) throw ConflictException("CARD_ALREADY_REPLACED", "Cette carte a déjà été remplacée")
            c to old
        }!!
        val limit = minOf(old.dailyLimitMinor, maxDailyLimit(c))
        return if (old.kind == CardKind.PHYSICAL) {
            issueAndSave(
                c, CardKind.PHYSICAL, limit, old.onlinePayments, checkBranch(branch ?: old.pickupBranch!!), old.id, idempotencyKey,
                props.replacementFeeMinor, "Remplacement de la carte •••• ${old.last4}",
            )
        } else {
            issueAndSave(c, CardKind.VIRTUAL, limit, true, null, old.id, idempotencyKey, 0, null)
        }
    }

    private fun checkBranch(branch: String): String =
        props.branches.firstOrNull { it.equals(branch.trim(), ignoreCase = true) }
            ?: throw BusinessException("INVALID_BRANCH", "Choisissez une agence dans la liste")

    private fun replay(customerId: UUID, key: String): Card? =
        jdbc.sql("SELECT * FROM card WHERE customer_id = :c AND idempotency_key = :k").param("c", customerId).param("k", key)
            .query(::map).optional().orElse(null)?.let(::view)

    private fun issueAndSave(
        c: Customer, kind: CardKind, dailyLimitMinor: Long, onlinePayments: Boolean, branch: String?, replaces: UUID?, key: String?,
        feeMinor: Long, feeLabel: String?,
    ): Card {
        val wallet = ledgerRepository.findCustomerWallet(c.id, Currency.USD)!!
        // Vérifié avant l'appel au processeur, pour ne pas émettre une carte que le client ne peut pas payer.
        if (feeMinor > 0 && ledgerRepository.balance(wallet.id) < feeMinor) {
            throw BusinessException("INSUFFICIENT_FUNDS", "Solde insuffisant pour les frais de carte")
        }
        val id = UUID.randomUUID()
        val processor = processors.current()
        val embossed = embossedName(c.fullName)
        val issued = try {
            processor.issue(CardIssueRequest(id, c.id, embossed, kind == CardKind.PHYSICAL, dailyLimitMinor, onlinePayments, branch))
        } catch (e: Exception) {
            log.warn("Émission de carte refusée par {} : {}", processor.name, e.message)
            throw processorDown(e)
        }
        try {
            tx.executeWithoutResult {
                lockCustomer(c.id)
                val now = now()
                val feeTx = if (feeMinor > 0) {
                    ledger.post(
                        PostingRequest(
                            idempotencyKey = "card-fee:$id", type = "CARD_FEE", description = feeLabel,
                            lines = listOf(
                                PostingLine(wallet.id, Direction.DEBIT, feeMinor),
                                PostingLine(ledgerRepository.findAccountByCode(FEES)!!.id, Direction.CREDIT, feeMinor),
                            ),
                        ),
                    ).transaction.id
                } else {
                    null
                }
                jdbc.sql(
                    """INSERT INTO card (id, customer_id, kind, status, processor, processor_token, network, last4, expiry_month, expiry_year,
                                         embossed_name, daily_limit_minor, online_payments, pickup_branch, replaces_card_id, idempotency_key,
                                         fee_transaction_id, created_at, activated_at, updated_at)
                       VALUES (:id, :c, :kind, :status, :p, :token, :network, :last4, :m, :y, :name, :limit, :online, :branch, :replaces, :key,
                               :fee, :now, :activated, :now)""",
                ).param("id", id).param("c", c.id).param("kind", kind.name)
                    .param("status", if (kind == CardKind.VIRTUAL) CardStatus.ACTIVE.name else CardStatus.ORDERED.name)
                    .param("p", processor.name).param("token", issued.token).param("network", issued.network.name).param("last4", issued.last4)
                    .param("m", issued.expiryMonth).param("y", issued.expiryYear).param("name", embossed).param("limit", dailyLimitMinor)
                    .param("online", onlinePayments).param("branch", branch).param("replaces", replaces).param("key", key).param("fee", feeTx)
                    .param("now", now).param("activated", if (kind == CardKind.VIRTUAL) now else null).update()
                audit.record(ActorType.CUSTOMER, c.id, if (kind == CardKind.VIRTUAL) "CARD_VIRTUAL_CREATED" else "CARD_PHYSICAL_ORDERED", "CARD", id,
                    listOfNotNull("•••• ${issued.last4}", branch, replaces?.let { "remplace $it" }).joinToString(" · "))
            }
        } catch (e: Exception) {
            // Commande annulée (solde, commande en double) : la carte émise chez le processeur est résiliée.
            runCatching { processor.setState(issued.token, ProcessorCardState.TERMINATED) }
                .onFailure { log.error("Carte {} émise mais non enregistrée, à résilier chez {}", issued.token, processor.name, it) }
            if (e is org.springframework.dao.DuplicateKeyException) {
                key?.let { replay(c.id, it) }?.let { return it }
                throw ConflictException("CARD_ALREADY_EXISTS", "Vous avez déjà une carte de ce type")
            }
            throw e
        }
        return view(row(id)!!)
    }

    /** « Chantal Mbuyi Kalala » devient « CHANTAL MBUYI » : majuscules sans accents, 26 caractères au plus. */
    private fun embossedName(full: String): String {
        val ascii = java.text.Normalizer.normalize(full, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .uppercase().replace(Regex("[^A-Z ]"), " ").trim().split(Regex("\\s+"))
        return ascii.take(2).joinToString(" ").take(26)
    }

    /** Gel : réversible, sans PIN, pour réagir vite quand on ne trouve plus sa carte. */
    fun freeze(customerId: UUID, cardId: UUID): Card = changeState(customerId, cardId, CardStatus.ACTIVE, CardStatus.FROZEN, "CARD_FROZEN") {
        "Seule une carte active peut être gelée"
    }

    fun unfreeze(customerId: UUID, cardId: UUID): Card = changeState(customerId, cardId, CardStatus.FROZEN, CardStatus.ACTIVE, "CARD_UNFROZEN") {
        "Cette carte n'est pas gelée"
    }

    private fun changeState(customerId: UUID, cardId: UUID, from: CardStatus, to: CardStatus, action: String, refusal: () -> String): Card {
        val card = owned(customerId, cardId)
        if (card.status == to) return view(card)
        if (view(card).status != from) throw BusinessException("INVALID_CARD_STATE", refusal())
        if (to == CardStatus.ACTIVE) requireEligible(customer(customerId))
        try {
            processors.byName(card.processor).setState(card.token, if (to == CardStatus.FROZEN) ProcessorCardState.SUSPENDED else ProcessorCardState.ACTIVE)
        } catch (e: Exception) {
            throw processorDown(e)
        }
        tx.executeWithoutResult {
            val updated = jdbc.sql("UPDATE card SET status = :to, updated_at = :now WHERE id = :id AND status = :from")
                .param("to", to.name).param("now", now()).param("id", cardId).param("from", from.name).update()
            if (updated == 1) audit.record(ActorType.CUSTOMER, customerId, action, "CARD", cardId)
        }
        return view(row(cardId)!!)
    }

    /** Perte, vol ou fraude : définitif. La carte se remplace ensuite par une nouvelle, avec un nouveau numéro. */
    fun block(customerId: UUID, cardId: UUID, reason: CardBlockReason): Card {
        val card = owned(customerId, cardId)
        if (card.status == CardStatus.BLOCKED) return view(card)
        if (card.status !in setOf(CardStatus.ACTIVE, CardStatus.FROZEN, CardStatus.READY_FOR_PICKUP)) {
            throw BusinessException("INVALID_CARD_STATE", "Cette carte ne peut pas être bloquée")
        }
        terminate(card)
        tx.executeWithoutResult {
            jdbc.sql("UPDATE card SET status = 'BLOCKED', block_reason = :r, blocked_at = :now, updated_at = :now WHERE id = :id")
                .param("r", reason.name).param("now", now()).param("id", cardId).update()
            audit.record(ActorType.CUSTOMER, customerId, "CARD_BLOCKED", "CARD", cardId, "${reason.name} · •••• ${card.last4}")
            notifications.cardBlocked(customerId, cardId, card.kind == CardKind.VIRTUAL, card.last4)
        }
        return view(row(cardId)!!)
    }

    /** Suppression d'une carte virtuelle : le numéro ne marche plus ; le client peut en créer une nouvelle. */
    fun delete(customerId: UUID, cardId: UUID) {
        val card = owned(customerId, cardId)
        if (card.kind != CardKind.VIRTUAL) {
            throw BusinessException("CARD_NOT_VIRTUAL", "Une carte physique ne se supprime pas : bloquez-la si vous l'avez perdue")
        }
        if (card.status != CardStatus.BLOCKED) terminate(card)
        tx.executeWithoutResult {
            jdbc.sql("UPDATE card SET status = 'DELETED', deleted_at = :now, updated_at = :now WHERE id = :id")
                .param("now", now()).param("id", cardId).update()
            audit.record(ActorType.CUSTOMER, customerId, "CARD_VIRTUAL_DELETED", "CARD", cardId, "•••• ${card.last4}")
        }
    }

    private fun terminate(card: Row) {
        try {
            processors.byName(card.processor).setState(card.token, ProcessorCardState.TERMINATED)
        } catch (e: Exception) {
            throw processorDown(e)
        }
    }

    /** Carte physique remise en agence : le client l'active avec le code du pli et choisit son PIN de carte. */
    fun activate(customerId: UUID, cardId: UUID, activationCode: String, cardPin: String): Card {
        val card = owned(customerId, cardId)
        if (card.kind != CardKind.PHYSICAL) throw BusinessException("CARD_NOT_PHYSICAL", "Une carte virtuelle est active dès sa création")
        if (card.status == CardStatus.ACTIVE) return view(card)
        if (card.status != CardStatus.READY_FOR_PICKUP) {
            throw BusinessException("CARD_NOT_READY", "Votre carte n'est pas encore arrivée à l'agence. Nous vous prévenons par SMS.")
        }
        requireEligible(customer(customerId))
        cd.capitalinvest.platform.auth.PinPolicy.validate(cardPin)
        val code = activationCode.filter { it.isDigit() }
        val ok = try {
            processors.byName(card.processor).activate(card.token, code, cardPin)
        } catch (e: Exception) {
            throw processorDown(e)
        }
        if (!ok) throw BusinessException("INVALID_ACTIVATION_CODE", "Code d'activation incorrect. Il est imprimé sur le pli remis avec la carte.")
        tx.executeWithoutResult {
            jdbc.sql("UPDATE card SET status = 'ACTIVE', activated_at = :now, updated_at = :now WHERE id = :id AND status = 'READY_FOR_PICKUP'")
                .param("now", now()).param("id", cardId).update()
            audit.record(ActorType.CUSTOMER, customerId, "CARD_ACTIVATED", "CARD", cardId, "•••• ${card.last4}")
        }
        return view(row(cardId)!!)
    }

    fun setControls(customerId: UUID, cardId: UUID, dailyLimitMinor: Long, onlinePayments: Boolean): Card {
        val card = owned(customerId, cardId)
        if (card.status !in LIVE) throw BusinessException("INVALID_CARD_STATE", "Cette carte n'est plus en service")
        val c = customer(customerId)
        val max = maxDailyLimit(c)
        if (dailyLimitMinor < 100 || dailyLimitMinor > max) {
            throw BusinessException("INVALID_CARD_LIMIT", "Le plafond journalier doit être entre 1,00 \$ et ${cd.capitalinvest.platform.notifications.NotificationService.money(max)}")
        }
        // Une carte virtuelle sert surtout en ligne : on ne la coupe pas d'internet (la geler suffit).
        val online = if (card.kind == CardKind.VIRTUAL) true else onlinePayments
        try {
            processors.byName(card.processor).setControls(card.token, dailyLimitMinor, online)
        } catch (e: Exception) {
            throw processorDown(e)
        }
        tx.executeWithoutResult {
            jdbc.sql("UPDATE card SET daily_limit_minor = :l, online_payments = :o, updated_at = :now WHERE id = :id")
                .param("l", dailyLimitMinor).param("o", online).param("now", now()).param("id", cardId).update()
            audit.record(ActorType.CUSTOMER, customerId, "CARD_CONTROLS_CHANGED", "CARD", cardId, "plafond $dailyLimitMinor · en ligne $online")
        }
        return view(row(cardId)!!)
    }

    /** Numéro complet, demandé au processeur à chaque fois (PIN vérifié par l'appelant), jamais stocké ni journalisé. */
    fun details(customerId: UUID, cardId: UUID): CardDetails {
        val card = owned(customerId, cardId)
        if (view(card).status !in setOf(CardStatus.ACTIVE, CardStatus.FROZEN)) {
            throw BusinessException("INVALID_CARD_STATE", "Le numéro ne s'affiche que pour une carte en service")
        }
        val d = try {
            processors.byName(card.processor).reveal(card.token)
        } catch (e: Exception) {
            throw processorDown(e)
        }
        audit.record(ActorType.CUSTOMER, customerId, "CARD_DETAILS_SHOWN", "CARD", cardId, "•••• ${card.last4}")
        return CardDetails(card.id, d.pan, d.cvv, card.expiryMonth, card.expiryYear, card.embossedName)
    }

    fun authorizations(customerId: UUID, cardId: UUID, limit: Int = 20): List<CardAuthorization> {
        owned(customerId, cardId)
        return jdbc.sql("SELECT * FROM card_authorization WHERE card_id = :c ORDER BY created_at DESC LIMIT :l")
            .param("c", cardId).param("l", limit.coerceIn(1, 100)).query(CardAuthorizationService::map).list()
    }

    /**
     * Avancement de la fabrication, annoncé par le processeur : validée → en fabrication → livrée en agence.
     * Le client est prévenu quand la carte l'attend à l'agence.
     */
    fun productionAdvanced(cardId: UUID, to: CardStatus): Card {
        val allowed = mapOf(CardStatus.IN_PRODUCTION to CardStatus.ORDERED, CardStatus.READY_FOR_PICKUP to CardStatus.IN_PRODUCTION)
        val from = allowed[to] ?: throw BusinessException("INVALID_CARD_STATE", "Étape de fabrication inconnue : $to")
        tx.executeWithoutResult {
            val card = row(cardId, lock = true) ?: throw NotFoundException("CARD_NOT_FOUND", "Carte introuvable")
            if (card.status == to) return@executeWithoutResult
            if (card.status != from) throw BusinessException("INVALID_CARD_STATE", "La carte est ${card.status}, pas $from")
            jdbc.sql("UPDATE card SET status = :to, updated_at = :now WHERE id = :id").param("to", to.name).param("now", now()).param("id", cardId).update()
            audit.record(ActorType.SYSTEM, null, "CARD_$to", "CARD", cardId)
            if (to == CardStatus.READY_FOR_PICKUP) notifications.cardReady(card.customerId, cardId, card.pickupBranch!!, card.last4)
        }
        return view(row(cardId)!!)
    }
}
