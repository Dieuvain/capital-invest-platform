package cd.capitalinvest.platform

import cd.capitalinvest.platform.backoffice.AgentRole
import cd.capitalinvest.platform.backoffice.AgentService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KycApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private val password = "mot-de-passe-solide"

    private fun jpeg(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + Random.nextBytes(2_000)

    private fun upload(s: Session, kind: String, bytes: ByteArray = jpeg(), type: String = "image/jpeg") =
        mvc.multipart("/v1/me/kyc/documents/$kind") {
            file(MockMultipartFile("file", "photo.jpg", type, bytes))
            header("Authorization", "Bearer ${s.token}")
        }

    private fun submit(s: Session, idType: String = "VOTER_CARD", birthDate: String = "1990-05-12") = mvc.post("/v1/me/kyc/submission") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"idType":"$idType","idNumber":"CE-123 456","birthDate":"$birthDate"}"""
    }

    private fun agent(email: String, role: AgentRole): String {
        agents.create(null, email, "Agent $email", password, role)
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.body()["accessToken"].asText()
    }

    /** Client avec les trois photos envoyées et une demande en attente. Renvoie l'id de la demande. */
    private fun pendingRequest(s: Session): String {
        upload(s, "ID_FRONT").andExpect { status { isCreated() } }
        upload(s, "ID_BACK").andExpect { status { isCreated() } }
        upload(s, "SELFIE").andExpect { status { isCreated() } }
        return submit(s).andExpect { status { isCreated() } }.body()["request"]["id"].asText()
    }

    @Test
    fun `parcours complet - photos, demande, examen par un agent, niveau 2`() {
        val alice = register("+243970000701")
        val selfie = jpeg()
        upload(alice, "ID_FRONT").andExpect { status { isCreated() } }
        upload(alice, "ID_BACK").andExpect { status { isCreated() } }
        val selfieId = upload(alice, "SELFIE", selfie).andExpect { status { isCreated() } }.body()["id"].asText()
        val requestId = submit(alice).andExpect {
            status { isCreated() }
            jsonPath("$.kycLevel") { value(1) }
            jsonPath("$.request.status") { value("PENDING") }
        }.body()["request"]["id"].asText()

        val token = agent("agent.kyc@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/kyc") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(requestId) }
            jsonPath("$[0].screeningResult") { value("CLEAR") }
        }
        val photo = mvc.get("/v1/backoffice/kyc/$requestId/documents/$selfieId") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                header { string("Cache-Control", "no-store") }
            }.andReturn().response.contentAsByteArray
        assertContentEquals(selfie, photo)

        mvc.post("/v1/backoffice/kyc/$requestId/approve") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("APPROVED") }
        }
        mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            jsonPath("$.kycLevel") { value(2) }
            jsonPath("$.perTransactionMinor") { value(400_000) }
        }
        assertTrue(mvc.get("/v1/simulators/sms/${alice.phone}").body()["text"].asText().contains("niveau 2"))

        val actions = jdbc.sql("SELECT action FROM audit_log ORDER BY created_at").query(String::class.java).list()
        assertTrue(actions.containsAll(listOf("KYC_SUBMITTED", "KYC_DOCUMENT_VIEWED", "KYC_APPROVED")))
    }

    @Test
    fun `les photos sont chiffrees en base`() {
        val alice = register("+243970000702")
        val bytes = jpeg()
        upload(alice, "SELFIE", bytes).andExpect { status { isCreated() } }
        val stored = jdbc.sql("SELECT encrypted FROM kyc_document").query { rs, _ -> rs.getBytes(1) }.single()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains(String(bytes.copyOfRange(4, 64), Charsets.ISO_8859_1)))
        assertEquals(bytes.size + 12 + 16, stored.size) // IV + étiquette GCM
    }

    @Test
    fun `un fichier qui n'est pas une photo est refuse`() {
        val alice = register("+243970000703")
        upload(alice, "SELFIE", "%PDF-1.7 faux".toByteArray(), "image/jpeg").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("DOCUMENT_TYPE_INVALID") }
        }
    }

    @Test
    fun `pieces manquantes, mineur et double demande sont refuses`() {
        val alice = register("+243970000704")
        upload(alice, "ID_FRONT")
        submit(alice).andExpect { jsonPath("$.code") { value("KYC_DOCUMENT_MISSING") } } // pas de selfie
        upload(alice, "SELFIE")
        submit(alice, "VOTER_CARD").andExpect { jsonPath("$.code") { value("KYC_DOCUMENT_MISSING") } } // pas de verso
        submit(alice, "PASSPORT", birthDate = LocalDate.now().minusYears(17).toString())
            .andExpect { jsonPath("$.code") { value("KYC_UNDERAGE") } }
        submit(alice, "PASSPORT").andExpect { status { isCreated() } }
        submit(alice, "PASSPORT").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("KYC_REQUEST_PENDING") }
        }
    }

    @Test
    fun `une correspondance sanctions ne peut etre validee que par la conformite`() {
        val alice = register("+243970000705")
        jdbc.sql("UPDATE customer SET full_name = 'Sanction MUTOMBO Fictif' WHERE phone = :p").param("p", alice.phone).update()
        val requestId = pendingRequest(alice)

        val kycAgent = agent("agent@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/kyc/$requestId") { header("Authorization", "Bearer $kycAgent") }
            .andExpect { jsonPath("$.screeningResult") { value("HIT") } }
        mvc.post("/v1/backoffice/kyc/$requestId/approve") { header("Authorization", "Bearer $kycAgent") }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("SCREENING_REVIEW_REQUIRED") }
        }
        val compliance = agent("conformite@capitalinvest.cd", AgentRole.COMPLIANCE)
        mvc.post("/v1/backoffice/kyc/$requestId/approve") { header("Authorization", "Bearer $compliance") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `un refus est motive, le client reste au niveau 1 et peut redemander`() {
        val alice = register("+243970000706")
        val requestId = pendingRequest(alice)
        val token = agent("agent@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.post("/v1/backoffice/kyc/$requestId/reject") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Photo de la pièce floue"}"""
        }.andExpect { jsonPath("$.status") { value("REJECTED") } }
        mvc.post("/v1/backoffice/kyc/$requestId/approve") { header("Authorization", "Bearer $token") }
            .andExpect { jsonPath("$.code") { value("KYC_ALREADY_DECIDED") } }

        mvc.get("/v1/me/kyc") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            jsonPath("$.kycLevel") { value(1) }
            jsonPath("$.request.status") { value("REJECTED") }
            jsonPath("$.request.rejectionReason") { value("Photo de la pièce floue") }
            jsonPath("$.request.screeningResult") { doesNotExist() }
        }
        submit(alice).andExpect { status { isCreated() } }
    }

    @Test
    fun `un agent ne peut ouvrir que les pieces de la demande examinee`() {
        val alice = register("+243970000707")
        val bob = register("+243970000708")
        val aliceRequest = pendingRequest(alice)
        val bobDoc = upload(bob, "SELFIE").body()["id"].asText()
        val token = agent("agent@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/kyc/$aliceRequest/documents/$bobDoc") { header("Authorization", "Bearer $token") }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `jetons clients et agents ne sont pas interchangeables`() {
        val alice = register("+243970000709")
        val token = agent("agent@capitalinvest.cd", AgentRole.KYC_AGENT)
        mvc.get("/v1/backoffice/kyc").andExpect { status { isUnauthorized() } }
        mvc.get("/v1/backoffice/kyc") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isUnauthorized() } }
        mvc.get("/v1/me") { header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
        mvc.post("/v1/backoffice/agents") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"x@capitalinvest.cd","fullName":"X","password":"$password","role":"ADMIN"}"""
        }.andExpect { jsonPath("$.code") { value("ADMIN_ONLY") } }
    }

    @Test
    fun `5 mauvais mots de passe bloquent le compte agent`() {
        agents.create(null, "agent@capitalinvest.cd", "Agent", password, AgentRole.KYC_AGENT)
        fun login(pw: String) = mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"agent@capitalinvest.cd","password":"$pw"}"""
        }
        repeat(4) { login("faux").andExpect { status { isUnauthorized() } } }
        login("faux").andExpect { status { isLocked() } }
        login(password).andExpect { status { isLocked() } }
    }
}
