package cd.capitalinvest.platform.ledger

import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.Currency
import cd.capitalinvest.platform.common.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

data class PostingResult(val transaction: LedgerTransaction, val replayed: Boolean)

/**
 * Seul point d'entrée pour faire bouger de l'argent.
 *
 * Règles garanties :
 * - au moins deux lignes, montants strictement positifs, total des débits = total des crédits ;
 * - tous les comptes existent, sont actifs et dans la même devise ;
 * - un compte qui n'autorise pas le négatif ne passe jamais sous zéro (vérifié sous verrou) ;
 * - une même clé d'idempotence rejouée renvoie la transaction d'origine, une clé réutilisée
 *   pour une autre demande est refusée.
 */
@Service
class LedgerService(private val repository: LedgerRepository) {

    @Transactional
    fun post(request: PostingRequest): PostingResult {
        validateShape(request)
        val hash = hashOf(request)
        val transactionId = UUID.randomUUID()

        if (!repository.insertTransaction(transactionId, request.idempotencyKey, hash, request.type, request.description)) {
            val (existingId, existingHash) = repository.findTransactionHeaderByKey(request.idempotencyKey)
                ?: error("Clé d'idempotence introuvable après conflit")
            if (existingHash != hash) {
                throw ConflictException("IDEMPOTENCY_KEY_REUSED", "Cette clé d'idempotence a déjà servi pour une autre opération")
            }
            return PostingResult(repository.findTransaction(existingId)!!, replayed = true)
        }

        val accountIds = request.lines.map { it.accountId }.toSet()
        val accounts = repository.lockAccounts(accountIds).associateBy { it.id }
        val missing = accountIds - accounts.keys
        if (missing.isNotEmpty()) throw NotFoundException("ACCOUNT_NOT_FOUND", "Compte introuvable : ${missing.first()}")

        val inactive = accounts.values.firstOrNull { it.status != AccountStatus.ACTIVE }
        if (inactive != null) throw BusinessException("ACCOUNT_NOT_ACTIVE", "Le compte ${inactive.id} n'est pas actif")

        val currencies = accounts.values.map { it.currency }.toSet()
        if (currencies.size != 1) throw BusinessException("CURRENCY_MISMATCH", "Tous les comptes doivent être dans la même devise")
        val currency: Currency = currencies.single()

        request.lines.forEach { repository.insertEntry(transactionId, it, currency) }

        accounts.values.filter { !it.allowNegative }.forEach { account ->
            if (repository.balance(account.id) < 0) {
                throw BusinessException("INSUFFICIENT_FUNDS", "Solde insuffisant sur le compte ${account.id}")
            }
        }

        return PostingResult(repository.findTransaction(transactionId)!!, replayed = false)
    }

    @Transactional(readOnly = true)
    fun balance(accountId: UUID): Long {
        repository.findAccount(accountId) ?: throw NotFoundException("ACCOUNT_NOT_FOUND", "Compte introuvable : $accountId")
        return repository.balance(accountId)
    }

    private fun validateShape(request: PostingRequest) {
        if (request.idempotencyKey.isBlank() || request.idempotencyKey.length > 200) {
            throw BusinessException("INVALID_IDEMPOTENCY_KEY", "Clé d'idempotence vide ou trop longue")
        }
        if (request.lines.size < 2) throw BusinessException("UNBALANCED", "Une écriture demande au moins deux lignes")
        if (request.lines.any { it.amountMinor <= 0 }) {
            throw BusinessException("INVALID_AMOUNT", "Chaque montant doit être strictement positif")
        }
        val debits = request.lines.filter { it.direction == Direction.DEBIT }.fold(0L) { acc, l -> Math.addExact(acc, l.amountMinor) }
        val credits = request.lines.filter { it.direction == Direction.CREDIT }.fold(0L) { acc, l -> Math.addExact(acc, l.amountMinor) }
        if (debits != credits) {
            throw BusinessException("UNBALANCED", "Débits ($debits) et crédits ($credits) doivent être égaux")
        }
    }

    private fun hashOf(request: PostingRequest): String {
        val canonical = buildString {
            append(request.type).append('|')
            request.lines.sortedWith(compareBy({ it.accountId }, { it.direction }, { it.amountMinor }))
                .forEach { append(it.accountId).append(':').append(it.direction).append(':').append(it.amountMinor).append(';') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
