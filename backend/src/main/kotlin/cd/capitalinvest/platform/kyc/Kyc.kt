package cd.capitalinvest.platform.kyc

import cd.capitalinvest.platform.notifications.NotificationService
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.ForbiddenException
import cd.capitalinvest.platform.common.ActorType
import cd.capitalinvest.platform.common.AuditLog
import cd.capitalinvest.platform.common.BusinessException
import cd.capitalinvest.platform.common.ConflictException
import cd.capitalinvest.platform.common.NotFoundException
import cd.capitalinvest.platform.customers.CustomerRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneOffset
import java.util.UUID

enum class DocumentKind { ID_FRONT, ID_BACK, SELFIE }
enum class IdType { VOTER_CARD, PASSPORT }
enum class KycStatus { PENDING, APPROVED, REJECTED }

data class KycDocumentView(val id: UUID, val kind: DocumentKind, val contentType: String, val sizeBytes: Int, val createdAt: OffsetDateTime)

data class KycRequestView(
    val id: UUID,
    val customerId: UUID,
    val customerName: String,
    val customerPhone: String,
    val idType: IdType,
    val idNumber: String,
    val birthDate: LocalDate,
    val idFrontDoc: UUID,
    val idBackDoc: UUID?,
    val selfieDoc: UUID,
    val screeningResult: String,
    val screeningDetail: String?,
    val status: KycStatus,
    val decidedBy: UUID?,
    val decidedAt: OffsetDateTime?,
    val rejectionReason: String?,
    val createdAt: OffsetDateTime,
)

/** Ce que le client voit de sa demande : ni le résultat du contrôle sanctions, ni l'agent. */
data class MyKycView(val kycLevel: Int, val request: MyKycRequest?)
data class MyKycRequest(val id: UUID, val status: KycStatus, val rejectionReason: String?, val createdAt: OffsetDateTime)

data class DocumentContent(val contentType: String, val bytes: ByteArray)

@Service
class KycService(
    private val jdbc: JdbcClient,
    private val cipher: DocumentCipher,
    private val screening: SanctionsScreening,
    private val customers: CustomerRepository,
    private val audit: AuditLog,
    private val notifications: NotificationService,
    private val props: KycProperties,
    private val clock: Clock,
) {
    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    // ---- Côté client -------------------------------------------------------------------------

    @Transactional
    fun upload(customerId: UUID, kind: DocumentKind, contentType: String?, bytes: ByteArray): KycDocumentView {
        if (bytes.isEmpty()) throw BusinessException("DOCUMENT_EMPTY", "Fichier vide")
        if (bytes.size > props.maxFileBytes) throw BusinessException("DOCUMENT_TOO_LARGE", "Fichier de ${props.maxFileBytes / 1024 / 1024} Mo au plus")
        // Le type déclaré ne suffit pas : on vérifie la signature du fichier.
        val detected = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "image/png"
            else -> null
        }
        if (detected == null || (contentType != null && contentType != detected)) {
            throw BusinessException("DOCUMENT_TYPE_INVALID", "Photo JPEG ou PNG attendue")
        }
        val id = UUID.randomUUID()
        val createdAt = now()
        jdbc.sql(
            """INSERT INTO kyc_document (id, customer_id, kind, content_type, size_bytes, sha256, encrypted, created_at)
               VALUES (:id, :c, :k, :t, :s, :h, :e, :at)""",
        )
            .param("id", id).param("c", customerId).param("k", kind.name).param("t", detected).param("s", bytes.size)
            .param("h", sha256(bytes)).param("e", cipher.encrypt(bytes)).param("at", createdAt)
            .update()
        audit.record(ActorType.CUSTOMER, customerId, "KYC_DOCUMENT_UPLOADED", "KYC_DOCUMENT", id, kind.name)
        return KycDocumentView(id, kind, detected, bytes.size, createdAt)
    }

    /** Demande de passage au KYC 2 avec les dernières pièces envoyées. Le contrôle sanctions est fait tout de suite. */
    @Transactional
    fun submit(customerId: UUID, idType: IdType, idNumber: String, birthDate: LocalDate): MyKycView {
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        if (customer.kycLevel >= 2) throw ConflictException("KYC_ALREADY_LEVEL_2", "Votre compte est déjà au niveau 2")
        if (Period.between(birthDate, LocalDate.now(clock)).years < props.minimumAge) {
            throw BusinessException("KYC_UNDERAGE", "Il faut avoir ${props.minimumAge} ans pour le niveau 2")
        }
        val pending = jdbc.sql("SELECT count(*) FROM kyc_request WHERE customer_id = :c AND status = 'PENDING'")
            .param("c", customerId).query(Long::class.java).single()
        if (pending > 0) throw ConflictException("KYC_REQUEST_PENDING", "Une demande est déjà en cours d'examen")

        val front = latestDocument(customerId, DocumentKind.ID_FRONT)
            ?: throw BusinessException("KYC_DOCUMENT_MISSING", "Photo du recto de la pièce manquante")
        val selfie = latestDocument(customerId, DocumentKind.SELFIE)
            ?: throw BusinessException("KYC_DOCUMENT_MISSING", "Selfie manquant")
        val back = latestDocument(customerId, DocumentKind.ID_BACK)
        if (idType == IdType.VOTER_CARD && back == null) {
            throw BusinessException("KYC_DOCUMENT_MISSING", "Photo du verso de la carte d'électeur manquante")
        }

        val result = screening.screen(customer.fullName, birthDate)
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO kyc_request (id, customer_id, id_type, id_number, birth_date, id_front_doc, id_back_doc, selfie_doc,
                                        screening_result, screening_detail, status, created_at)
               VALUES (:id, :c, :t, :n, :b, :f, :bk, :s, :sr, :sd, 'PENDING', :at)""",
        )
            .param("id", id).param("c", customerId).param("t", idType.name).param("n", idNumber.trim().uppercase())
            .param("b", birthDate).param("f", front).param("bk", back).param("s", selfie)
            .param("sr", if (result.hit) "HIT" else "CLEAR").param("sd", result.detail).param("at", now())
            .update()
        audit.record(ActorType.CUSTOMER, customerId, "KYC_SUBMITTED", "KYC_REQUEST", id)
        if (result.hit) audit.record(ActorType.SYSTEM, null, "SCREENING_HIT", "KYC_REQUEST", id, result.detail)
        return mine(customerId)
    }

    @Transactional(readOnly = true)
    fun mine(customerId: UUID): MyKycView {
        val customer = customers.findById(customerId) ?: throw NotFoundException("CUSTOMER_NOT_FOUND", "Client introuvable")
        val last = jdbc.sql(
            "SELECT id, status, rejection_reason, created_at FROM kyc_request WHERE customer_id = :c ORDER BY created_at DESC LIMIT 1",
        ).param("c", customerId).query { rs, _ ->
            MyKycRequest(
                rs.getObject("id", UUID::class.java), KycStatus.valueOf(rs.getString("status")),
                rs.getString("rejection_reason"), rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }.list().firstOrNull()
        return MyKycView(customer.kycLevel, last)
    }

    // ---- Côté back-office --------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun list(status: KycStatus, limit: Int): List<KycRequestView> =
        jdbc.sql("$SELECT_REQUEST WHERE r.status = :s ORDER BY r.created_at LIMIT :l")
            .param("s", status.name).param("l", limit).query(::mapRequest).list()

    @Transactional
    fun get(agent: AgentPrincipal, id: UUID): KycRequestView {
        val request = find(id)
        audit.record(ActorType.AGENT, agent.id, "KYC_REQUEST_VIEWED", "KYC_REQUEST", id)
        return request
    }

    /** Un agent ne peut ouvrir que les pièces rattachées à la demande qu'il examine ; chaque ouverture est tracée. */
    @Transactional
    fun document(agent: AgentPrincipal, requestId: UUID, documentId: UUID): DocumentContent {
        val request = find(requestId)
        if (documentId !in listOfNotNull(request.idFrontDoc, request.idBackDoc, request.selfieDoc)) {
            throw NotFoundException("DOCUMENT_NOT_FOUND", "Pièce introuvable pour cette demande")
        }
        val doc = jdbc.sql("SELECT content_type, encrypted FROM kyc_document WHERE id = :id").param("id", documentId)
            .query { rs, _ -> DocumentContent(rs.getString("content_type"), cipher.decrypt(rs.getBytes("encrypted"))) }
            .single()
        audit.record(ActorType.AGENT, agent.id, "KYC_DOCUMENT_VIEWED", "KYC_DOCUMENT", documentId)
        return doc
    }

    /**
     * Validation : le client passe au niveau 2. Une demande signalée par le contrôle sanctions
     * ne peut être validée que par la conformité.
     */
    @Transactional
    fun approve(agent: AgentPrincipal, id: UUID): KycRequestView {
        val request = lockPending(id)
        if (request.screeningResult == "HIT" && agent.role != AgentRole.COMPLIANCE) {
            throw ForbiddenException("SCREENING_REVIEW_REQUIRED", "Correspondance sanctions ou PEP : décision réservée à la conformité")
        }
        decide(id, agent, KycStatus.APPROVED, null)
        jdbc.sql("UPDATE customer SET kyc_level = 2 WHERE id = :c").param("c", request.customerId).update()
        audit.record(ActorType.AGENT, agent.id, "KYC_APPROVED", "KYC_REQUEST", id)
        notifications.kycDecided(request.customerId, id, approved = true)
        return find(id)
    }

    @Transactional
    fun reject(agent: AgentPrincipal, id: UUID, reason: String): KycRequestView {
        val request = lockPending(id)
        decide(id, agent, KycStatus.REJECTED, reason.trim().take(300))
        audit.record(ActorType.AGENT, agent.id, "KYC_REJECTED", "KYC_REQUEST", id, reason)
        notifications.kycDecided(request.customerId, id, approved = false)
        return find(id)
    }

    private fun decide(id: UUID, agent: AgentPrincipal, status: KycStatus, reason: String?) {
        jdbc.sql("UPDATE kyc_request SET status = :s, decided_by = :a, decided_at = :at, rejection_reason = :r WHERE id = :id")
            .param("s", status.name).param("a", agent.id).param("at", now()).param("r", reason).param("id", id).update()
    }

    private fun lockPending(id: UUID): KycRequestView {
        val status = jdbc.sql("SELECT status FROM kyc_request WHERE id = :id FOR UPDATE").param("id", id)
            .query(String::class.java).list().firstOrNull() ?: throw NotFoundException("KYC_REQUEST_NOT_FOUND", "Demande introuvable")
        if (status != "PENDING") throw ConflictException("KYC_ALREADY_DECIDED", "Demande déjà traitée ($status)")
        return find(id)
    }

    private fun find(id: UUID): KycRequestView =
        jdbc.sql("$SELECT_REQUEST WHERE r.id = :id").param("id", id).query(::mapRequest).list().firstOrNull()
            ?: throw NotFoundException("KYC_REQUEST_NOT_FOUND", "Demande introuvable")

    private fun latestDocument(customerId: UUID, kind: DocumentKind): UUID? =
        jdbc.sql("SELECT id FROM kyc_document WHERE customer_id = :c AND kind = :k ORDER BY created_at DESC LIMIT 1")
            .param("c", customerId).param("k", kind.name).query(UUID::class.java).list().firstOrNull()

    private fun mapRequest(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = KycRequestView(
        id = rs.getObject("id", UUID::class.java),
        customerId = rs.getObject("customer_id", UUID::class.java),
        customerName = rs.getString("full_name"),
        customerPhone = rs.getString("phone"),
        idType = IdType.valueOf(rs.getString("id_type")),
        idNumber = rs.getString("id_number"),
        birthDate = rs.getObject("birth_date", LocalDate::class.java),
        idFrontDoc = rs.getObject("id_front_doc", UUID::class.java),
        idBackDoc = rs.getObject("id_back_doc", UUID::class.java),
        selfieDoc = rs.getObject("selfie_doc", UUID::class.java),
        screeningResult = rs.getString("screening_result"),
        screeningDetail = rs.getString("screening_detail"),
        status = KycStatus.valueOf(rs.getString("status")),
        decidedBy = rs.getObject("decided_by", UUID::class.java),
        decidedAt = rs.getObject("decided_at", OffsetDateTime::class.java),
        rejectionReason = rs.getString("rejection_reason"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val SELECT_REQUEST =
            "SELECT r.*, c.full_name, c.phone FROM kyc_request r JOIN customer c ON c.id = r.customer_id"
    }
}
