package cd.capitalinvest.platform.payments

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Un contact favori : le surnom donné par le client, et le nom vérifié du destinataire pour ne pas se tromper. */
data class Favorite(val id: UUID, val nickname: String, val phone: String, val displayName: String)

data class FavoriteRequest(val phone: String, val nickname: String)

/**
 * Contacts favoris pour envoyer en un geste. Ajouter un favori passe par la vérification du destinataire
 * (comptée dans la limite par heure) : les favoris ne permettent pas de collecter des noms. Un compte devenu
 * suspendu ou fermé disparaît de la liste.
 */
@Service
class FavoriteService(
    private val jdbc: JdbcClient,
    private val recipients: RecipientService,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    @Transactional(noRollbackFor = [BusinessException::class])
    fun save(customerId: UUID, phone: String, nickname: String): Favorite {
        val name = nickname.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty() || name.length > 30) throw BusinessException("INVALID_NICKNAME", "Donnez un surnom de 30 caractères au plus")
        val recipient = recipients.lookup(customerId, phone)
        val recipientId = customers.findByPhone(recipient.phone)!!.id
        val count = jdbc.sql("SELECT count(*) FROM favorite_recipient WHERE customer_id = :c AND recipient_id <> :r")
            .param("c", customerId).param("r", recipientId).query(Int::class.java).single()
        if (count >= MAX) throw BusinessException("FAVORITES_LIMIT", "Vous avez déjà $MAX favoris. Supprimez-en un d'abord.")
        // Le même destinataire ajouté deux fois : le surnom est mis à jour.
        val id = jdbc.sql(
            """INSERT INTO favorite_recipient (id, customer_id, recipient_id, nickname, created_at) VALUES (:id, :c, :r, :n, :now)
               ON CONFLICT (customer_id, recipient_id) DO UPDATE SET nickname = EXCLUDED.nickname
               RETURNING id""",
        ).param("id", UUID.randomUUID()).param("c", customerId).param("r", recipientId).param("n", name)
            .param("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)).query(UUID::class.java).single()
        return Favorite(id, name, recipient.phone, recipient.displayName)
    }

    /** Par surnom, sans les comptes qui ne peuvent plus recevoir d'argent. */
    fun list(customerId: UUID): List<Favorite> =
        jdbc.sql(
            """SELECT f.id, f.nickname, c.phone, c.full_name FROM favorite_recipient f JOIN customer c ON c.id = f.recipient_id
               WHERE f.customer_id = :c AND c.status = 'ACTIVE' ORDER BY lower(f.nickname)""",
        ).param("c", customerId).query { rs, _ ->
            Favorite(rs.getObject("id", UUID::class.java), rs.getString("nickname"), rs.getString("phone"), RecipientService.displayName(rs.getString("full_name")))
        }.list()

    fun delete(customerId: UUID, id: UUID) {
        val n = jdbc.sql("DELETE FROM favorite_recipient WHERE id = :id AND customer_id = :c").param("id", id).param("c", customerId).update()
        if (n == 0) throw NotFoundException("FAVORITE_NOT_FOUND", "Favori introuvable")
    }

    companion object {
        const val MAX = 20
    }
}

@RestController
@RequestMapping("/v1/me/favorites")
class FavoriteController(private val favorites: FavoriteService) {
    @GetMapping
    fun list(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID) = favorites.list(customerId)

    @PostMapping
    fun save(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestBody b: FavoriteRequest) =
        favorites.save(customerId, b.phone, b.nickname)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @PathVariable id: UUID) = favorites.delete(customerId, id)
}
