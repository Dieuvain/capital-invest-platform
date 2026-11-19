package cd.capitalinvest.platform.common

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

enum class ActorType { AGENT, CUSTOMER, SYSTEM }

/** Une ligne du journal, avec le nom de l'agent quand c'est un agent qui a agi. */
data class AuditEntry(
    val id: UUID,
    val actorType: ActorType,
    val actorId: UUID?,
    val actorName: String?,
    val action: String,
    val targetType: String,
    val targetId: UUID,
    val detail: String?,
    val createdAt: OffsetDateTime,
)

/** Journal d'audit en ajout seul : qui a fait quoi sur quel objet. */
@Repository
class AuditLog(private val jdbc: JdbcClient) {
    fun record(actorType: ActorType, actorId: UUID?, action: String, targetType: String, targetId: UUID, detail: String? = null) {
        jdbc.sql(
            """INSERT INTO audit_log (id, actor_type, actor_id, action, target_type, target_id, detail)
               VALUES (:id, :at, :aid, :action, :tt, :tid, :detail)""",
        )
            .param("id", UUID.randomUUID()).param("at", actorType.name).param("aid", actorId)
            .param("action", action).param("tt", targetType).param("tid", targetId).param("detail", detail?.take(500))
            .update()
    }

    fun recent(limit: Int): List<AuditEntry> =
        jdbc.sql(
            """SELECT l.*, a.full_name FROM audit_log l
               LEFT JOIN backoffice_agent a ON l.actor_type = 'AGENT' AND a.id = l.actor_id
               ORDER BY l.created_at DESC LIMIT :limit""",
        ).param("limit", limit).query { rs, _ ->
            AuditEntry(
                rs.getObject("id", UUID::class.java), ActorType.valueOf(rs.getString("actor_type")),
                rs.getObject("actor_id", UUID::class.java), rs.getString("full_name"), rs.getString("action"),
                rs.getString("target_type"), rs.getObject("target_id", UUID::class.java), rs.getString("detail"),
                rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list()
}
