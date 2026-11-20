package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.AuthService
import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.Customer
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitExceededException
import cd.capitalinvest.platform.limits.LimitsService
import cd.capitalinvest.platform.notifications.NotificationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties("capital-invest.pots")
data class PotsProperties(
    /** Cagnottes ouvertes à la fois, par organisateur. */
    val maxOpenPerOwner: Int = 3,
)

enum class PotStatus { OPEN, CLOSED }

data class PotContribution(
    val id: UUID,
    /** Nom du participant ; null quand il a choisi de le cacher et que ce n'est pas l'organisateur qui regarde. */
    val name: String?,
    /** Montant : visible de l'organisateur, et du participant pour sa propre participation. */
    val amountMinor: Long?,
    val message: String?,
    val mine: Boolean,
    val createdAt: OffsetDateTime,
)

data class Pot(
    val id: UUID,
    val code: String,
    val title: String,
    val description: String?,
    val targetMinor: Long?,
    val status: PotStatus,
    /** Prénom et initiale de l'organisateur, pour que le participant sache à qui il donne. */
    val ownerName: String,
    val mine: Boolean,
    val collectedMinor: Long,
    val contributors: Int,
    val myTotalMinor: Long,
    val progressPercent: Int?,
    /** Lien du QR et du message de partage : il ouvre la cagnotte dans l'app. */
    val shareLink: String,
    val contributions: List<PotContribution>,
    val createdAt: OffsetDateTime,
    val closedAt: OffsetDateTime?,
    /** Montant versé à l'organisateur à la clôture. */
    val paidOutMinor: Long?,
)

data class PotRequest(
    @field:Size(max = 40) val title: String,
    @field:Size(max = 140) val description: String? = null,
    val targetMinor: Long? = null,
)

data class PotContributionRequest(
    @field:Positive val amountMinor: Long,
    val pin: String,
    @field:Size(max = 80) val message: String? = null,
    val hideName: Boolean = false,
)

/**
 * Cagnotte : un client collecte l'argent de plusieurs personnes (deuil, mariage, cotisation).
 * Chaque cagnotte a son compte dans le grand livre ; l'argent y attend la clôture, qui le verse sur
 * le portefeuille de l'organisateur. Participer ne coûte rien et compte dans les plafonds d'envoi du participant.
 */
@Service
class PotService(
    private val jdbc: JdbcClient,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val notifications: NotificationService,
    private val props: PotsProperties,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun customer(id: UUID): Customer = customers.findById(id) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")

    private class Row(
        val id: UUID, val ownerId: UUID, val code: String, val title: String, val description: String?, val targetMinor: Long?,
        val accountId: UUID, val status: PotStatus, val createdAt: OffsetDateTime, val closedAt: OffsetDateTime?, val payoutTx: UUID?,
    )

    private fun rows(where: String, param: Pair<String, Any>, lock: Boolean = false): List<Row> =
        jdbc.sql("SELECT * FROM pot WHERE $where${if (lock) " FOR UPDATE" else ""}").param(param.first, param.second).query { rs, _ ->
            Row(
                rs.getObject("id", UUID::class.java), rs.getObject("owner_id", UUID::class.java), rs.getString("code"),
                rs.getString("title"), rs.getString("description"), rs.getObject("target_minor") as Long?,
                rs.getObject("account_id", UUID::class.java), PotStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("closed_at", OffsetDateTime::class.java),
                rs.getObject("payout_transaction_id", UUID::class.java),
            )
        }.list()

    private fun byCode(code: String, lock: Boolean = false): Row {
        val clean = code.trim().uppercase().replace(" ", "").replace("-", "")
        return rows("code = :c", "c" to clean, lock).firstOrNull()
            ?: throw NotFoundException("POT_NOT_FOUND", "Aucune cagnotte avec ce code. Vérifiez les 6 caractères.")
    }

    private fun owned(ownerId: UUID, id: UUID, lock: Boolean = false): Row =
        rows("id = :id", "id" to id, lock).firstOrNull()?.takeIf { it.ownerId == ownerId }
            ?: throw NotFoundException("POT_NOT_FOUND", "Cagnotte introuvable")

    @Transactional
    fun create(ownerId: UUID, r: PotRequest): Pot {
        val owner = customer(ownerId)
        if (owner.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu ou fermé")
        val title = r.title.trim()
        if (title.isEmpty() || title.length > 40) throw BusinessException("INVALID_POT_TITLE", "Donnez un nom de 1 à 40 caractères à la cagnotte")
        val description = r.description?.trim()?.ifEmpty { null }
        if (description != null && description.length > 140) throw BusinessException("INVALID_POT_DESCRIPTION", "140 caractères au plus pour le message")
        if (r.targetMinor != null && r.targetMinor <= 0) throw BusinessException("INVALID_POT_TARGET", "L'objectif doit être positif")
        // Verrou du client : deux créations simultanées ne dépassent pas le nombre de cagnottes ouvertes.
        jdbc.sql("SELECT id FROM customer WHERE id = :id FOR UPDATE").param("id", ownerId).query(UUID::class.java).single()
        val open = jdbc.sql("SELECT count(*) FROM pot WHERE owner_id = :o AND status = 'OPEN'").param("o", ownerId).query(Int::class.java).single()
        if (open >= props.maxOpenPerOwner) {
            throw LimitExceededException("POTS_LIMIT", "Déjà ${props.maxOpenPerOwner} cagnottes ouvertes : clôturez-en une d'abord")
        }
        val accountId = UUID.randomUUID()
        // Sens normal au crédit : de l'argent que Capital Invest doit à l'organisateur, comme son portefeuille.
        jdbc.sql(
            """INSERT INTO ledger_account (id, code, type, currency, normal_side, allow_negative, customer_id, status)
               VALUES (:id, NULL, 'CUSTOMER_POT', 'USD', 'CREDIT', FALSE, :c, 'ACTIVE')""",
        ).param("id", accountId).param("c", ownerId).update()
        val id = UUID.randomUUID()
        val code = generateSequence { newCode() }.first { c ->
            jdbc.sql("SELECT count(*) FROM pot WHERE code = :c").param("c", c).query(Int::class.java).single() == 0
        }
        jdbc.sql(
            """INSERT INTO pot (id, owner_id, code, title, description, target_minor, account_id, status, created_at)
               VALUES (:id, :o, :code, :t, :d, :target, :a, 'OPEN', :now)""",
        ).param("id", id).param("o", ownerId).param("code", code).param("t", title).param("d", description)
            .param("target", r.targetMinor).param("a", accountId).param("now", now()).update()
        return view(rows("id = :id", "id" to id).single(), ownerId)
    }

    private fun newCode(): String = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    fun mine(ownerId: UUID): List<Pot> =
        jdbc.sql("SELECT id FROM pot WHERE owner_id = :o ORDER BY (status = 'OPEN') DESC, created_at DESC LIMIT 20")
            .param("o", ownerId).query(UUID::class.java).list()
            .map { view(rows("id = :id", "id" to it).single(), ownerId) }

    fun get(viewerId: UUID, code: String): Pot = view(byCode(code), viewerId)

    @Transactional
    fun contribute(contributorId: UUID, code: String, idempotencyKey: String, r: PotContributionRequest): Pair<Pot, Boolean> {
        val ledgerKey = "pot-contribution:$contributorId:$idempotencyKey"
        ledgerRepository.findTransactionHeaderByKey(ledgerKey)?.let { (txId, _) ->
            val potId = jdbc.sql("SELECT pot_id FROM pot_contribution WHERE transaction_id = :t").param("t", txId).query(UUID::class.java).single()
            return view(rows("id = :id", "id" to potId).single(), contributorId) to true
        }
        val pot = byCode(code, lock = true)
        if (r.amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        if (pot.ownerId == contributorId) throw BusinessException("OWN_POT", "C'est votre cagnotte : partagez son code pour que vos proches participent")
        if (pot.status != PotStatus.OPEN) throw BusinessException("POT_CLOSED", "Cette cagnotte est clôturée")
        val owner = customer(pot.ownerId)
        if (owner.status != "ACTIVE") throw BusinessException("POT_CLOSED", "Cette cagnotte n'accepte plus de participations")
        val contributor = customer(contributorId)
        if (contributor.status != "ACTIVE") throw BusinessException("CUSTOMER_NOT_ACTIVE", "Votre compte est suspendu ou fermé")
        val message = r.message?.trim()?.ifEmpty { null }
        if (message != null && message.length > 80) throw BusinessException("INVALID_MESSAGE", "80 caractères au plus pour le message")
        val wallet = ledgerRepository.findCustomerWallet(contributorId, Currency.USD)!!
        ledgerRepository.lockAccounts(listOf(wallet.id, pot.accountId))
        if (ledgerRepository.balance(wallet.id) < r.amountMinor) throw BusinessException("INSUFFICIENT_FUNDS", "Solde insuffisant")
        limits.checkOutgoing(contributor, wallet.id, r.amountMinor)
        // L'organisateur doit pouvoir recevoir la cagnotte à la clôture : elle ne dépasse pas le solde maximal de son niveau.
        if (ledgerRepository.balance(pot.accountId) + r.amountMinor > limits.tier(owner).maxBalanceMinor) {
            throw LimitExceededException("POT_FULL", "Cette cagnotte a atteint le montant maximal que l'organisateur peut recevoir")
        }
        val tx = ledger.post(
            PostingRequest(
                idempotencyKey = ledgerKey,
                type = "POT_CONTRIBUTION",
                description = message,
                lines = listOf(
                    PostingLine(wallet.id, Direction.DEBIT, r.amountMinor),
                    PostingLine(pot.accountId, Direction.CREDIT, r.amountMinor),
                ),
            ),
        ).transaction
        jdbc.sql(
            """INSERT INTO pot_contribution (id, pot_id, contributor_id, amount_minor, message, hide_name, transaction_id, created_at)
               VALUES (:id, :p, :c, :a, :m, :h, :t, :now)""",
        ).param("id", UUID.randomUUID()).param("p", pot.id).param("c", contributorId).param("a", r.amountMinor)
            .param("m", message).param("h", r.hideName).param("t", tx.id).param("now", now()).update()
        notifications.potContribution(pot.ownerId, tx.id, pot.title, contributor.fullName, r.amountMinor, message, ledgerRepository.balance(pot.accountId))
        return view(pot, contributorId) to false
    }

    /** Clôture : tout l'argent collecté passe sur le portefeuille de l'organisateur, et plus personne ne peut participer. */
    @Transactional
    fun close(ownerId: UUID, id: UUID): Pot {
        val pot = owned(ownerId, id, lock = true)
        if (pot.status == PotStatus.CLOSED) return view(pot, ownerId)
        val owner = customer(ownerId)
        val wallet = ledgerRepository.findCustomerWallet(ownerId, Currency.USD)!!
        ledgerRepository.lockAccounts(listOf(wallet.id, pot.accountId))
        val collected = ledgerRepository.balance(pot.accountId)
        var payout: UUID? = null
        if (collected > 0) {
            try {
                limits.checkIncoming(owner, wallet.id, collected)
            } catch (e: LimitExceededException) {
                throw LimitExceededException(
                    "POT_PAYOUT_LIMIT",
                    "Votre solde dépasserait le maximum de votre niveau. Passez au niveau 2, ou retirez une partie de votre solde, puis clôturez.",
                )
            }
            payout = ledger.post(
                PostingRequest(
                    idempotencyKey = "pot-payout:${pot.id}",
                    type = "POT_PAYOUT",
                    description = null,
                    lines = listOf(PostingLine(pot.accountId, Direction.DEBIT, collected), PostingLine(wallet.id, Direction.CREDIT, collected)),
                ),
            ).transaction.id
        }
        jdbc.sql("UPDATE pot SET status = 'CLOSED', closed_at = :now, payout_transaction_id = :tx WHERE id = :id")
            .param("now", now()).param("tx", payout).param("id", pot.id).update()
        jdbc.sql("UPDATE ledger_account SET status = 'CLOSED' WHERE id = :a").param("a", pot.accountId).update()
        return view(rows("id = :id", "id" to pot.id).single(), ownerId)
    }

    private fun view(p: Row, viewerId: UUID): Pot {
        val owner = customer(p.ownerId)
        val mine = p.ownerId == viewerId
        val contributions = jdbc.sql(
            """SELECT pc.id, pc.contributor_id, pc.amount_minor, pc.message, pc.hide_name, pc.created_at, c.full_name
               FROM pot_contribution pc JOIN customer c ON c.id = pc.contributor_id
               WHERE pc.pot_id = :p ORDER BY pc.created_at DESC""",
        ).param("p", p.id).query { rs, _ ->
            val own = rs.getObject("contributor_id", UUID::class.java) == viewerId
            Triple(
                rs.getObject("contributor_id", UUID::class.java),
                rs.getLong("amount_minor"),
                PotContribution(
                    id = rs.getObject("id", UUID::class.java),
                    name = if (mine || own || !rs.getBoolean("hide_name")) rs.getString("full_name") else null,
                    amountMinor = if (mine || own) rs.getLong("amount_minor") else null,
                    message = rs.getString("message"),
                    mine = own,
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                ),
            )
        }.list()
        val collected = contributions.sumOf { it.second }
        val paidOut = p.payoutTx?.let { collected }
        return Pot(
            id = p.id,
            code = p.code,
            title = p.title,
            description = p.description,
            targetMinor = p.targetMinor,
            status = p.status,
            ownerName = shortName(owner.fullName),
            mine = mine,
            collectedMinor = collected,
            contributors = contributions.map { it.first }.distinct().size,
            myTotalMinor = contributions.filter { it.first == viewerId }.sumOf { it.second },
            progressPercent = p.targetMinor?.let { (collected * 100 / it).coerceIn(0, 100).toInt() },
            shareLink = "capitalinvest://pot?c=${p.code}",
            contributions = contributions.map { it.third }.take(if (mine) 100 else 20),
            createdAt = p.createdAt,
            closedAt = p.closedAt,
            paidOutMinor = paidOut,
        )
    }

    /** Organisateur dont le compte se ferme : une cagnotte ouverte bloque la fermeture. */
    fun openTitles(ownerId: UUID): List<String> =
        jdbc.sql("SELECT title FROM pot WHERE owner_id = :o AND status = 'OPEN' ORDER BY created_at").param("o", ownerId).query(String::class.java).list()

    companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** « Chantal Mbuyi Kalala » devient « Chantal M. ». */
        fun shortName(full: String): String {
            val parts = full.trim().split(Regex("\\s+"))
            return if (parts.size < 2) parts[0] else "${parts[0]} ${parts[1].first().uppercaseChar()}."
        }
    }
}

@RestController
class PotController(private val pots: PotService, private val auth: AuthService) {
    @PostMapping("/v1/me/pots")
    fun create(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @Valid @RequestBody body: PotRequest): ResponseEntity<Pot> =
        ResponseEntity.status(HttpStatus.CREATED).body(pots.create(customerId, body))

    @GetMapping("/v1/me/pots")
    fun mine(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = pots.mine(customerId)

    @PostMapping("/v1/me/pots/{id}/close")
    fun close(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = pots.close(customerId, id)

    @GetMapping("/v1/pots/{code}")
    fun get(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable code: String) = pots.get(customerId, code)

    @PostMapping("/v1/pots/{code}/contributions")
    fun contribute(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @PathVariable code: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody body: PotContributionRequest,
    ): ResponseEntity<Pot> {
        // Vérifié dans sa propre transaction : un mauvais PIN reste compté.
        auth.confirmWithPin(customerId, body.pin)
        val (pot, replayed) = pots.contribute(customerId, code, key, body)
        return ResponseEntity.status(if (replayed) HttpStatus.OK else HttpStatus.CREATED).body(pot)
    }
}
