package cd.capitalinvest.platform.cards

import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import cd.capitalinvest.platform.ledger.Direction
import cd.capitalinvest.platform.ledger.LedgerRepository
import cd.capitalinvest.platform.ledger.LedgerService
import cd.capitalinvest.platform.ledger.PostingLine
import cd.capitalinvest.platform.ledger.PostingRequest
import cd.capitalinvest.platform.limits.LimitsProperties
import cd.capitalinvest.platform.limits.LimitsService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Autorisation en temps réel d'un paiement par carte (analyse UML, séquence 3.3) : le processeur demande,
 * Capital Invest répond « acceptée » ou « refusée avec un motif ». Une autorisation acceptée débite tout de suite
 * le portefeuille au profit du compte de règlement du processeur ; une annulation (achat annulé, remboursement
 * du commerçant) le recrédite. Chaque demande est gardée, refus compris, pour que le client comprenne un refus.
 */
@Service
class CardAuthorizationService(
    private val jdbc: JdbcClient,
    private val cards: CardService,
    private val customers: CustomerRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledger: LedgerService,
    private val limits: LimitsService,
    private val audit: AuditLog,
    limitsProps: LimitsProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    companion object {
        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): CardAuthorization {
            val code = rs.getString("decline_code")
            return CardAuthorization(
                id = rs.getObject("id", UUID::class.java),
                cardId = rs.getObject("card_id", UUID::class.java),
                amountMinor = rs.getLong("amount_minor"),
                currency = rs.getString("currency"),
                merchantName = rs.getString("merchant_name"),
                merchantCity = rs.getString("merchant_city"),
                online = rs.getBoolean("online"),
                status = AuthorizationStatus.valueOf(rs.getString("status")),
                declineCode = code,
                declineMessage = code?.let(::declineMessage),
                transactionId = rs.getObject("transaction_id", UUID::class.java),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }

        fun declineMessage(code: String): String = when (code) {
            "CARD_FROZEN" -> "Carte gelée : dégelez-la dans l'app pour payer"
            "CARD_BLOCKED" -> "Carte bloquée"
            "CARD_NOT_ACTIVE" -> "Carte pas encore activée"
            "CARD_EXPIRED" -> "Carte expirée"
            "ONLINE_PAYMENTS_DISABLED" -> "Paiements en ligne désactivés pour cette carte"
            "CARD_DAILY_LIMIT" -> "Plafond journalier de la carte atteint"
            "INSUFFICIENT_FUNDS" -> "Solde insuffisant"
            "ACCOUNT_NOT_ACTIVE" -> "Compte suspendu ou bloqué"
            "CURRENCY_NOT_SUPPORTED" -> "Devise non acceptée"
            "PIN_RECENTLY_RESET" -> "Paiements suspendus 24 h après un PIN oublié"
            "LIMIT_PER_TRANSACTION" -> "Montant supérieur au plafond par opération de votre niveau"
            "LIMIT_DAILY", "LIMIT_DAILY_PERSONAL" -> "Plafond journalier de votre compte atteint"
            else -> "Paiement refusé"
        }
    }

    private val zone = ZoneId.of(limitsProps.timezone)

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun find(cardId: UUID, reference: String): CardAuthorization? =
        jdbc.sql("SELECT * FROM card_authorization WHERE card_id = :c AND processor_reference = :r")
            .param("c", cardId).param("r", reference).query(::map).optional().orElse(null)

    private class Declined(val code: String) : RuntimeException(code)

    /** Décision sur une demande du processeur. Rejouer la même référence renvoie la même décision. */
    fun authorize(cardId: UUID, r: AuthorizationRequest): CardAuthorization {
        if (r.amountMinor <= 0) throw BusinessException("INVALID_AMOUNT", "Le montant doit être positif")
        if (r.processorReference.isBlank()) throw BusinessException("INVALID_REFERENCE", "Référence du processeur absente")
        find(cardId, r.processorReference)?.let { return it }
        val id = UUID.randomUUID()
        return try {
            tx.execute { approve(id, cardId, r) }!!
        } catch (d: Declined) {
            tx.execute {
                insert(id, cardId, r, AuthorizationStatus.DECLINED, d.code, null)
                find(cardId, r.processorReference)!!
            }!!
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            find(cardId, r.processorReference)!!
        }
    }

    private fun approve(id: UUID, cardId: UUID, r: AuthorizationRequest): CardAuthorization {
        val card = cards.row(cardId, lock = true) ?: throw NotFoundException("CARD_NOT_FOUND", "Carte introuvable")
        val customer = customers.findById(card.customerId)!!
        val today = clock.instant().atZone(zone).toLocalDate()
        when {
            customer.status != "ACTIVE" -> throw Declined("ACCOUNT_NOT_ACTIVE")
            card.status == CardStatus.FROZEN -> throw Declined("CARD_FROZEN")
            card.status in setOf(CardStatus.BLOCKED, CardStatus.DELETED) -> throw Declined("CARD_BLOCKED")
            card.status != CardStatus.ACTIVE -> throw Declined("CARD_NOT_ACTIVE")
            card.expired(today) -> throw Declined("CARD_EXPIRED")
            r.currency != "USD" -> throw Declined("CURRENCY_NOT_SUPPORTED")
            r.online && !card.onlinePayments -> throw Declined("ONLINE_PAYMENTS_DISABLED")
        }
        val wallet = ledgerRepository.findCustomerWallet(customer.id, Currency.USD)!!
        ledgerRepository.lockAccounts(listOf(wallet.id))
        val since = today.atStartOfDay(zone).toOffsetDateTime()
        val spent = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM card_authorization WHERE card_id = :c AND status = 'APPROVED' AND created_at >= :s")
            .param("c", cardId).param("s", since).query(Long::class.java).single()
        if (spent + r.amountMinor > card.dailyLimitMinor) throw Declined("CARD_DAILY_LIMIT")
        // Mêmes protections que les autres sorties d'argent : plafonds du niveau, suspension après un PIN oublié.
        try {
            limits.checkOutgoing(customer, wallet.id, r.amountMinor)
        } catch (e: BusinessException) {
            throw Declined(e.code)
        }
        if (ledgerRepository.balance(wallet.id) < r.amountMinor) throw Declined("INSUFFICIENT_FUNDS")
        val transaction = ledger.post(
            PostingRequest(
                idempotencyKey = "card-authorization:$id",
                type = "CARD_PAYMENT",
                description = r.merchantName.take(60),
                lines = listOf(
                    PostingLine(wallet.id, Direction.DEBIT, r.amountMinor),
                    PostingLine(ledgerRepository.findAccountByCode(CardService.SETTLEMENT)!!.id, Direction.CREDIT, r.amountMinor),
                ),
            ),
        ).transaction
        insert(id, cardId, r, AuthorizationStatus.APPROVED, null, transaction.id)
        return find(cardId, r.processorReference)!!
    }

    private fun insert(id: UUID, cardId: UUID, r: AuthorizationRequest, status: AuthorizationStatus, declineCode: String?, txId: UUID?) {
        jdbc.sql(
            """INSERT INTO card_authorization (id, card_id, processor_reference, amount_minor, currency, merchant_name, merchant_city, online,
                                               status, decline_code, transaction_id, created_at)
               VALUES (:id, :c, :ref, :a, :cur, :m, :city, :online, :s, :code, :tx, :now)""",
        ).param("id", id).param("c", cardId).param("ref", r.processorReference.take(64)).param("a", r.amountMinor)
            .param("cur", r.currency.take(3)).param("m", r.merchantName.trim().take(60).ifEmpty { "Commerçant" })
            .param("city", r.merchantCity?.trim()?.take(40)).param("online", r.online).param("s", status.name)
            .param("code", declineCode).param("tx", txId).param("now", now()).update()
    }

    /** Achat annulé ou remboursé par le commerçant : l'argent revient sur le portefeuille, une seule fois. */
    fun reverse(authorizationId: UUID): CardAuthorization = tx.execute {
        val a = jdbc.sql("SELECT * FROM card_authorization WHERE id = :id FOR UPDATE").param("id", authorizationId)
            .query(::map).optional().orElse(null) ?: throw NotFoundException("AUTHORIZATION_NOT_FOUND", "Autorisation introuvable")
        if (a.status == AuthorizationStatus.REVERSED) return@execute a
        if (a.status != AuthorizationStatus.APPROVED) throw BusinessException("AUTHORIZATION_NOT_APPROVED", "Ce paiement a été refusé : rien à rendre")
        val card = cards.row(a.cardId)!!
        val wallet = ledgerRepository.findCustomerWallet(card.customerId, Currency.USD)!!
        val reversal = ledger.post(
            PostingRequest(
                idempotencyKey = "card-reversal:${a.id}",
                type = "CARD_REFUND",
                description = a.merchantName,
                lines = listOf(
                    PostingLine(ledgerRepository.findAccountByCode(CardService.SETTLEMENT)!!.id, Direction.DEBIT, a.amountMinor),
                    PostingLine(wallet.id, Direction.CREDIT, a.amountMinor),
                ),
            ),
        ).transaction
        jdbc.sql("UPDATE card_authorization SET status = 'REVERSED', reversal_transaction_id = :t, reversed_at = :now WHERE id = :id")
            .param("t", reversal.id).param("now", now()).param("id", a.id).update()
        audit.record(ActorType.SYSTEM, null, "CARD_PAYMENT_REVERSED", "CARD", a.cardId, "${a.merchantName} · ${a.amountMinor}")
        jdbc.sql("SELECT * FROM card_authorization WHERE id = :id").param("id", a.id).query(::map).single()
    }!!
}
