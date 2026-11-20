package cd.capitalinvest.platform

import cd.capitalinvest.platform.payments.PotService
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cagnottes : un client collecte, les autres participent avec un code, l'argent arrive à la clôture. */
class PotsApiTest : ApiTest() {

    private fun create(s: Session, title: String, target: Long? = null, description: String? = null) = mvc.post("/v1/me/pots") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("title" to title, "targetMinor" to target, "description" to description))
    }

    private fun give(s: Session, code: String, amountMinor: Long, key: String, message: String? = null, hideName: Boolean = false, pin: String = "2580") =
        mvc.post("/v1/pots/$code/contributions") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("amountMinor" to amountMinor, "pin" to pin, "message" to message, "hideName" to hideName))
        }

    private fun pot(s: Session, code: String): JsonNode =
        mvc.get("/v1/pots/$code") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()

    private fun close(s: Session, id: String) = mvc.post("/v1/me/pots/$id/close") { header("Authorization", "Bearer ${s.token}") }

    private fun history(s: Session) = mvc.get("/v1/me/history") { header("Authorization", "Bearer ${s.token}") }
        .andExpect { status { isOk() } }.body()["items"]

    private fun notifications(s: Session) = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${s.token}") }
        .andExpect { status { isOk() } }.body()["items"]

    @Test
    fun `collecte pour un deuil - participations, noms caches, cloture vers le compte`() {
        val chantal = register("+243970004001", fullName = "Chantal Mbuyi Kalala")
        val papy = register("+243970004002", fullName = "Papy Ilunga")
        val grace = register("+243970004003", fullName = "Grâce Kabeya")
        topupConfirmed(papy, 10_000, "p-top")
        topupConfirmed(grace, 10_000, "g-top")

        val created = create(chantal, "  Deuil de Papa Mbuyi ", target = 50_000, description = "Pour les funérailles de samedi")
            .andExpect { status { isCreated() } }.body()
        val code = created["code"].asText()
        assertTrue(Regex("^[A-HJ-NP-Z2-9]{6}$").matches(code), code)
        assertEquals("Deuil de Papa Mbuyi", created["title"].asText())
        assertEquals("capitalinvest://pot?c=$code", created["shareLink"].asText())
        assertEquals("OPEN", created["status"].asText())

        // Le participant voit à qui il donne, sans le nom complet de l'organisatrice.
        val seen = pot(papy, code.lowercase().chunked(3).joinToString(" "))
        assertEquals("Chantal M.", seen["ownerName"].asText())
        assertEquals(false, seen["mine"].asBoolean())

        give(papy, code, 2_500, "c1", message = "Courage à la famille").andExpect { status { isCreated() } }
        give(papy, code, 2_500, "c1", message = "Courage à la famille").andExpect { status { isOk() } } // double appui
        give(grace, code, 1_000, "c2", hideName = true).andExpect { status { isCreated() } }
        give(papy, code, 500, "c3").andExpect { status { isCreated() } }
        assertEquals(10_000 - 3_000, balance(papy)) // sans frais
        assertEquals(10_000 - 1_000, balance(grace))
        assertEquals(0, balance(chantal)) // l'argent attend dans la cagnotte

        // L'organisatrice voit tout : noms, montants, messages.
        val mine = mvc.get("/v1/me/pots") { header("Authorization", "Bearer ${chantal.token}") }.body()[0]
        assertEquals(4_000, mine["collectedMinor"].asLong())
        assertEquals(2, mine["contributors"].asInt())
        assertEquals(8, mine["progressPercent"].asInt())
        assertEquals(listOf("Papy Ilunga", "Grâce Kabeya", "Papy Ilunga"), mine["contributions"].map { it["name"].asText() })
        assertEquals(listOf(500L, 1_000L, 2_500L), mine["contributions"].map { it["amountMinor"].asLong() })
        // Grâce a caché son nom : Papy ne le voit pas, ni les montants des autres.
        val forPapy = pot(papy, code)
        assertEquals(3_000, forPapy["myTotalMinor"].asLong())
        assertTrue(forPapy["contributions"][1]["name"].isNull)
        assertTrue(forPapy["contributions"][1]["amountMinor"].isNull)
        assertEquals(500, forPapy["contributions"][0]["amountMinor"].asLong())

        // Historique et notifications.
        history(papy)[0].let {
            assertEquals("POT_CONTRIBUTION", it["kind"].asText())
            assertEquals("Deuil de Papa Mbuyi", it["counterpartyName"].asText())
            assertEquals(500, it["amountMinor"].asLong())
            assertEquals(0, it["feeMinor"].asLong())
        }
        assertEquals("Courage à la famille", history(papy)[1]["note"].asText())
        val received = notifications(chantal).filter { it["kind"].asText() == "POT_CONTRIBUTION_RECEIVED" }
        assertEquals(3, received.size)
        assertEquals("Papy Ilunga a versé 25,00 \$ à « Deuil de Papa Mbuyi » : « Courage à la famille ». Total collecté : 25,00 \$.", received.last()["body"].asText())
        // Dans l'app seulement : pas un SMS par participation.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'POT_CONTRIBUTION_RECEIVED' AND sms_text IS NOT NULL").query(Int::class.java).single())

        // Clôture : tout passe sur le compte de l'organisatrice, la cagnotte n'accepte plus rien.
        val closed = close(chantal, created["id"].asText()).andExpect { status { isOk() } }.body()
        assertEquals("CLOSED", closed["status"].asText())
        assertEquals(4_000, closed["paidOutMinor"].asLong())
        assertEquals(4_000, balance(chantal))
        close(chantal, created["id"].asText()).andExpect { status { isOk() } }
        assertEquals(4_000, balance(chantal)) // une seule fois
        give(papy, code, 100, "c4").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("POT_CLOSED") } }
        history(chantal)[0].let {
            assertEquals("POT_PAYOUT", it["kind"].asText())
            assertEquals(4_000, it["amountMinor"].asLong())
        }
        assertEquals("POT_PAID_OUT", notifications(chantal)[0]["kind"].asText())
    }

    @Test
    fun `refus - code inconnu, sa propre cagnotte, mauvais PIN, solde, nombre de cagnottes, autre organisateur`() {
        val owner = register("+243970004011")
        val other = register("+243970004012")
        topupConfirmed(other, 1_000, "o-top")
        val created = create(owner, "Mariage de Jonas").body()
        val id = created["id"].asText()
        val code = created["code"].asText()

        mvc.get("/v1/pots/ZZZZZZ") { header("Authorization", "Bearer ${other.token}") }
            .andExpect { status { isNotFound() }; jsonPath("$.code") { value("POT_NOT_FOUND") } }
        give(owner, code, 100, "x1").andExpect { jsonPath("$.code") { value("OWN_POT") } }
        give(other, code, 100, "x2", pin = "1111").andExpect { status { isUnauthorized() } }
        give(other, code, 5_000, "x3").andExpect { jsonPath("$.code") { value("INSUFFICIENT_FUNDS") } }
        create(owner, " ").andExpect { jsonPath("$.code") { value("INVALID_POT_TITLE") } }
        create(owner, "Deux").andExpect { status { isCreated() } }
        create(owner, "Trois").andExpect { status { isCreated() } }
        create(owner, "Quatre").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("POTS_LIMIT") } }
        close(other, id).andExpect { status { isNotFound() } }

        // Une cagnotte ouverte empêche de fermer son compte.
        val check = mvc.get("/v1/me/closure") { header("Authorization", "Bearer ${owner.token}") }.body()
        assertTrue(check["blockers"].any { it["code"].asText() == "POT_OPEN" }, check.toString())
    }

    @Test
    fun `participer compte dans le plafond journalier, et la cagnotte ne depasse pas ce que l'organisateur peut recevoir`() {
        val owner = register("+243970004021")
        val giver = register("+243970004022")
        setKycLevel(giver, 2)
        topupConfirmed(giver, 400_000, "big-top")
        val code = create(owner, "Cotisation").body()["code"].asText()
        // Niveau 1 de l'organisateur : 1 000 $ au plus.
        give(giver, code, 100_000, "b1").andExpect { status { isCreated() } }
        give(giver, code, 1, "b2").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("POT_FULL") } }

        // Le participant de niveau 1 : 600 $ par jour, cagnottes comprises.
        val small = register("+243970004023")
        topupConfirmed(small, 20_000, "s-top-1")
        topupConfirmed(small, 20_000, "s-top-2")
        topupConfirmed(small, 20_000, "s-top-3")
        topupConfirmed(small, 20_000, "s-top-4")
        val other = create(register("+243970004024"), "Projet").body()["code"].asText()
        repeat(3) { give(small, other, 20_000, "s$it").andExpect { status { isCreated() } } }
        give(small, other, 100, "s-over").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("LIMIT_DAILY") } }
    }

    @Test
    fun `nom court de l'organisateur`() {
        assertEquals("Chantal M.", PotService.shortName("Chantal  Mbuyi Kalala"))
        assertEquals("Jonas", PotService.shortName("Jonas"))
    }
}
