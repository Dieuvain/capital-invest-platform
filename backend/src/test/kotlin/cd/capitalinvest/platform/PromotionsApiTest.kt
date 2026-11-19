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
import org.springframework.test.web.servlet.put
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromotionsApiTest : ApiTest() {

    @Autowired lateinit var agents: AgentService

    private val password = "mot-de-passe-solide"

    private fun agent(email: String, role: AgentRole): String {
        agents.create(null, email, "Agent $email", password, role)
        return mvc.post("/v1/backoffice/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.body()["accessToken"].asText()
    }

    private fun create(token: String, json: String) = mvc.post("/v1/backoffice/promotions") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    /** Après un saut d'horloge de plusieurs jours, la session a expiré : on se reconnecte. */
    private fun relogin(s: Session) = s.copy(token = loginRequest(s.phone, "2580").andExpect { status { isOk() } }.body()["accessToken"].asText())

    private fun titles(s: Session): List<String> =
        mvc.get("/v1/promotions") { header("Authorization", "Bearer ${s.token}") }
            .andExpect { status { isOk() } }.body().map { it["title"].asText() }

    @Test
    fun `le marketing publie une promotion et le client la voit dans l'app`() {
        val token = agent("marketing@capitalinvest.cd", AgentRole.MARKETING)
        create(token, """{"title":"Fête de l'indépendance","body":"Envois sans souci pendant tout le week-end.",
            "ctaLabel":"Envoyer","action":"TRANSFER","style":"GREEN","priority":5}""").andExpect {
            status { isCreated() }
            jsonPath("$.live") { value(true) }
        }
        create(token, """{"title":"Info","body":"Nouvelle agence à Kolwezi."}""").andExpect { status { isCreated() } }

        val alice = register("+243970000801")
        mvc.get("/v1/promotions") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            jsonPath("$[0].title") { value("Fête de l'indépendance") }
            jsonPath("$[0].action") { value("TRANSFER") }
            jsonPath("$[0].style") { value("GREEN") }
            jsonPath("$[0].audience") { doesNotExist() } // le ciblage reste interne
            jsonPath("$[1].title") { value("Info") }
            jsonPath("$[1].ctaLabel") { doesNotExist() }
        }
        val actions = jdbc.sql("SELECT action FROM audit_log WHERE target_type = 'PROMOTION'").query(String::class.java).list()
        assertEquals(listOf("PROMOTION_CREATED", "PROMOTION_CREATED"), actions)
    }

    @Test
    fun `la période et le niveau KYC décident qui voit quoi`() {
        val token = agent("admin@capitalinvest.cd", AgentRole.ADMIN)
        create(token, """{"title":"Passez au niveau 2","body":"Plafonds plus hauts.","ctaLabel":"Vérifier","action":"KYC","audience":"KYC1"}""")
        create(token, """{"title":"Merci","body":"Pour les clients vérifiés.","audience":"KYC2"}""")
        val start = testClock.instant().plus(Duration.ofDays(2))
        create(token, """{"title":"Bientôt","body":"Commence dans deux jours.","startsAt":"$start","endsAt":"${start.plus(Duration.ofDays(1))}"}""")
            .andExpect { status { isCreated() } }

        val alice = register("+243970000802")
        assertEquals(listOf("Passez au niveau 2"), titles(alice))
        setKycLevel(alice, 2)
        assertEquals(listOf("Merci"), titles(alice))

        testClock.advance(Duration.ofDays(2).plusHours(1))
        assertEquals(listOf("Merci", "Bientôt"), titles(relogin(alice)).sortedDescending())
        testClock.advance(Duration.ofDays(1))
        assertEquals(listOf("Merci"), titles(relogin(alice)))
    }

    @Test
    fun `désactiver une promotion la retire de l'app sans l'effacer`() {
        val token = agent("admin@capitalinvest.cd", AgentRole.ADMIN)
        val id = create(token, """{"title":"Soldes","body":"Jusqu'à dimanche."}""").body()["id"].asText()
        val alice = register("+243970000803")
        assertEquals(listOf("Soldes"), titles(alice))

        mvc.put("/v1/backoffice/promotions/$id") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"Soldes","body":"Jusqu'à dimanche.","active":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.active") { value(false) }
            jsonPath("$.live") { value(false) }
        }
        assertEquals(emptyList(), titles(alice))
        mvc.get("/v1/backoffice/promotions") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$[0].id") { value(id) }
        }
    }

    @Test
    fun `seuls le marketing et les administrateurs publient, et le contenu est contrôlé`() {
        val kyc = agent("kyc@capitalinvest.cd", AgentRole.KYC_AGENT)
        create(kyc, """{"title":"X","body":"Y"}""").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("MARKETING_ONLY") }
        }
        val token = agent("marketing@capitalinvest.cd", AgentRole.MARKETING)
        create(token, """{"title":"Sans action","body":"Bouton sans destination.","ctaLabel":"Go"}""").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("PROMOTION_INVALID") }
        }
        create(token, """{"title":"${"x".repeat(61)}","body":"Trop long."}""").andExpect { status { isBadRequest() } }
        create(token, """{"title":"Dates","body":"Fin avant début.","startsAt":"2026-12-10T00:00:00Z","endsAt":"2026-12-01T00:00:00Z"}""")
            .andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `l'app doit être connectée pour lire les promotions`() {
        mvc.get("/v1/promotions").andExpect { status { isUnauthorized() } }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { it.toByte() }

    private fun upload(token: String, id: String, bytes: ByteArray, type: String = "image/png") =
        mvc.multipart("/v1/backoffice/promotions/$id/image") {
            header("Authorization", "Bearer $token")
            file(MockMultipartFile("file", "visuel.png", type, bytes))
        }

    @Test
    fun `le graphiste envoie un visuel, l'app le recoit avec une adresse qui change a chaque version`() {
        val token = agent("marketing@capitalinvest.cd", AgentRole.MARKETING)
        // Sur un visuel, le texte est facultatif et toute l'image mène à l'action, sans texte de bouton.
        val id = create(token, """{"title":"Recharge offerte","action":"TOPUP"}""")
            .andExpect { status { isCreated() } }.body()["id"].asText()
        val alice = register("+243970000804")
        mvc.get("/v1/promotions") { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            jsonPath("$[0].imageUrl") { doesNotExist() }
            jsonPath("$[0].body") { doesNotExist() }
        }

        upload(token, id, png).andExpect {
            status { isOk() }
            jsonPath("$.imageType") { value("image/png") }
        }
        val url = mvc.get("/v1/promotions") { header("Authorization", "Bearer ${alice.token}") }.body()[0]["imageUrl"].asText()
        assertTrue(url.startsWith("/v1/promotions/$id/image?v="), url)
        val res = mvc.get(url) { header("Authorization", "Bearer ${alice.token}") }.andExpect {
            status { isOk() }
            content { contentType("image/png") }
            header { string("Cache-Control", "max-age=604800, private") }
        }.andReturn().response
        assertContentEquals(png, res.contentAsByteArray)

        testClock.advance(Duration.ofSeconds(5))
        upload(token, id, png).andExpect { status { isOk() } }
        val next = mvc.get("/v1/promotions") { header("Authorization", "Bearer ${alice.token}") }.body()[0]["imageUrl"].asText()
        assertTrue(next != url, "un nouveau visuel change l'adresse, le cache du téléphone est contourné")

        mvc.get("/v1/backoffice/promotions/$id/image") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        val actions = jdbc.sql("SELECT action FROM audit_log WHERE target_type = 'PROMOTION' ORDER BY created_at").query(String::class.java).list()
        assertEquals(listOf("PROMOTION_CREATED", "PROMOTION_IMAGE_UPLOADED", "PROMOTION_IMAGE_UPLOADED"), actions)
    }

    @Test
    fun `un visuel est verifie sur son contenu, et reste invisible pour qui ne voit pas la promotion`() {
        val token = agent("admin@capitalinvest.cd", AgentRole.ADMIN)
        val id = create(token, """{"title":"Clients vérifiés","audience":"KYC2"}""").body()["id"].asText()
        upload(token, id, "<svg onload=alert(1)>".toByteArray(), type = "image/png").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("PROMOTION_IMAGE_TYPE") }
        }
        upload(token, id, png + ByteArray(2 * 1024 * 1024)).andExpect { jsonPath("$.code") { value("PROMOTION_IMAGE_SIZE") } }
        upload(token, id, png).andExpect { status { isOk() } }

        val alice = register("+243970000805") // niveau 1
        mvc.get("/v1/promotions/$id/image") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isNotFound() } }
        mvc.get("/v1/promotions/$id/image").andExpect { status { isUnauthorized() } }

        val kyc = agent("kyc@capitalinvest.cd", AgentRole.KYC_AGENT)
        upload(kyc, id, png).andExpect { status { isForbidden() } }
    }
}
