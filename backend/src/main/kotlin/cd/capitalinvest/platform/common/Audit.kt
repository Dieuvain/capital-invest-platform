package cd.capitalinvest.platform.common

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

enum class ActorType { AGENT, CUSTOMER, SYSTEM }

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
}
