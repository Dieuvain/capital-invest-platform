package cd.capitalinvest.platform

import cd.capitalinvest.platform.cards.SimulatedCardProcessor
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cartes virtuelle et physique, avec le processeur de cartes simulé. */
class CardsApiTest : ApiTest() {

    private fun bearer(s: Session): String = "Bearer ${s.token}"

    private fun overview(s: Session): JsonNode = mvc.get("/v1/me/cards") { header("Authorization", bearer(s)) }.andExpect { status { isOk() } }.body()

    private fun createVirtual(s: Session) = mvc.post("/v1/me/cards/virtual") { header("Authorization", bearer(s)) }

    private fun orderPhysical(s: Session, key: String, branch: String = "Kolwezi - Agence principale", pin: String = "2580") =
        mvc.post("/v1/me/cards/physical") {
            header("Authorization", bearer(s))
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("pickupBranch" to branch, "pin" to pin))
        }

    private fun action(s: Session, cardId: String, action: String, body: Map<String, Any?>? = null, key: String? = null) =
        mvc.post("/v1/me/cards/$cardId/$action") {
            header("Authorization", bearer(s))
            if (key != null) header("Idempotency-Key", key)
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = json.writeValueAsString(body)
            }
        }

    private fun pay(cardId: String, amountMinor: Long, merchant: String = "Shoprite Kolwezi", online: Boolean = false, reference: String? = null): JsonNode =
        mvc.post("/v1/simulators/cards/$cardId/authorizations") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("amountMinor" to amountMinor, "merchantName" to merchant, "online" to online, "reference" to reference))
        }.andExpect { status { isOk() } }.body()

    private fun production(cardId: String) =
        mvc.post("/v1/simulators/cards/$cardId/production").andExpect { status { isOk() } }.body()

    private fun activationCode(cardId: String) =
        mvc.get("/v1/simulators/cards/$cardId/activation-code").andExpect { status { isOk() } }.body()["activationCode"].asText()

    private fun history(s: Session) = mvc.get("/v1/me/history") { header("Authorization", bearer(s)) }.andExpect { status { isOk() } }.body()["items"]

    private fun notifications(s: Session) = mvc.get("/v1/me/notifications") { header("Authorization", bearer(s)) }
        .andExpect { status { isOk() } }.body()["items"]

    private fun level2(phone: String, name: String = "Chantal Mbuyi Kalala"): Session = register(phone, fullName = name).also { setKycLevel(it, 2) }

    @Test
    fun `niveau 1 - pas de carte, l'app sait pourquoi`() {
        val s = register("+243970005001")
        val o = overview(s)
        assertFalse(o["eligible"].asBoolean())
        assertEquals(0, o["cards"].size())
        createVirtual(s).andExpect { status { isForbidden() }; jsonPath("$.code") { value("KYC_LEVEL_REQUIRED") } }
        orderPhysical(s, "k1").andExpect { status { isForbidden() }; jsonPath("$.code") { value("KYC_LEVEL_REQUIRED") } }
    }

    @Test
    fun `carte virtuelle - creee active, numero affiche avec le PIN, jamais stocke`() {
        val s = level2("+243970005002")
        val card = createVirtual(s).andExpect { status { isCreated() } }.body()
        assertEquals("VIRTUAL", card["kind"].asText())
        assertEquals("ACTIVE", card["status"].asText())
        assertEquals("VISA", card["network"].asText())
        assertEquals("CHANTAL MBUYI", card["embossedName"].asText())
        assertTrue(card["onlinePayments"].asBoolean())
        assertEquals(50_000, card["dailyLimitMinor"].asLong())
        assertTrue(Regex("^[0-9]{4}$").matches(card["last4"].asText()))
        val id = card["id"].asText()

        // Une seule carte virtuelle vivante.
        createVirtual(s).andExpect { status { isConflict() }; jsonPath("$.code") { value("CARD_ALREADY_EXISTS") } }

        action(s, id, "details", mapOf("pin" to "9999")).andExpect { status { isUnauthorized() } }
        val details = action(s, id, "details", mapOf("pin" to "2580"))
            .andExpect { status { isOk() }; header { string("Cache-Control", "no-store") } }.body()
        val pan = details["pan"].asText()
        assertEquals(16, pan.length)
        assertTrue(SimulatedCardProcessor.luhnValid(pan))
        assertTrue(pan.endsWith(card["last4"].asText()))
        assertEquals(3, details["cvv"].asText().length)
        assertEquals(card["expiryYear"].asInt(), details["expiryYear"].asInt())

        // Le numéro complet n'apparaît nulle part dans la base.
        val stored = jdbc.sql("SELECT row_to_json(c)::text FROM card c").query(String::class.java).list().joinToString()
        assertFalse(stored.contains(pan))
        assertFalse(jdbc.sql("SELECT coalesce(string_agg(detail, ' '), '') FROM audit_log").query(String::class.java).single().contains(pan))
    }

    @Test
    fun `paiement par carte - debite le portefeuille, historique, SMS, annulation rend l'argent`() {
        val s = level2("+243970005003")
        topupConfirmed(s, 10_000, "top-1")
        val id = createVirtual(s).andExpect { status { isCreated() } }.body()["id"].asText()

        val auth = pay(id, 2_550, merchant = "Jumia RDC", online = true, reference = "REF-1")
        assertEquals("APPROVED", auth["status"].asText())
        assertEquals(7_450, balance(s))
        // La même demande renvoyée après une coupure ne débite pas deux fois.
        assertEquals(auth["id"].asText(), pay(id, 2_550, merchant = "Jumia RDC", online = true, reference = "REF-1")["id"].asText())
        assertEquals(7_450, balance(s))

        val item = history(s)[0]
        assertEquals("CARD_PAYMENT", item["kind"].asText())
        assertEquals("Jumia RDC", item["counterpartyName"].asText())
        assertEquals(2_550, item["amountMinor"].asLong())
        val notif = notifications(s).first { it["kind"].asText() == "CARD_PAYMENT" }
        assertTrue(notif["body"].asText().contains("Jumia RDC"), notif.toString())

        assertEquals(2_550, overview(s)["cards"][0]["spentTodayMinor"].asLong())

        val reversed = mvc.post("/v1/simulators/cards/authorizations/${auth["id"].asText()}/reversal").andExpect { status { isOk() } }.body()
        assertEquals("REVERSED", reversed["status"].asText())
        assertEquals(10_000, balance(s))
        // Une seule fois.
        mvc.post("/v1/simulators/cards/authorizations/${auth["id"].asText()}/reversal").andExpect { status { isOk() } }
        assertEquals(10_000, balance(s))
        assertEquals("CARD_REFUND", history(s)[0]["kind"].asText())
        assertEquals(0, overview(s)["cards"][0]["spentTodayMinor"].asLong())
    }

    @Test
    fun `refus - solde, plafond de la carte, gel, et le client voit pourquoi`() {
        val s = level2("+243970005004")
        topupConfirmed(s, 100_000, "top-1")
        val id = createVirtual(s).andExpect { status { isCreated() } }.body()["id"].asText()

        // Plafond journalier de la carte : 500 $ par défaut.
        assertEquals("APPROVED", pay(id, 40_000)["status"].asText())
        val over = pay(id, 15_000)
        assertEquals("DECLINED", over["status"].asText())
        assertEquals("CARD_DAILY_LIMIT", over["declineCode"].asText())

        // Le client relève le plafond de sa carte, jusqu'au plafond journalier de son niveau.
        mvc.put("/v1/me/cards/$id/controls") {
            header("Authorization", bearer(s)); contentType = MediaType.APPLICATION_JSON
            content = """{"dailyLimitMinor":2000000,"onlinePayments":true}"""
        }.andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_CARD_LIMIT") } }
        mvc.put("/v1/me/cards/$id/controls") {
            header("Authorization", bearer(s)); contentType = MediaType.APPLICATION_JSON
            content = """{"dailyLimitMinor":80000,"onlinePayments":false}"""
        }.andExpect { status { isOk() }; jsonPath("$.dailyLimitMinor") { value(80000) }; jsonPath("$.onlinePayments") { value(true) } }
        assertEquals("APPROVED", pay(id, 15_000)["status"].asText())

        // 550 $ déjà payés aujourd'hui : 300 $ de plus dépasseraient les 800 $ choisis.
        assertEquals("CARD_DAILY_LIMIT", pay(id, 30_000)["declineCode"].asText())

        action(s, id, "freeze").andExpect { status { isOk() }; jsonPath("$.status") { value("FROZEN") } }
        val frozen = pay(id, 100)
        assertEquals("CARD_FROZEN", frozen["declineCode"].asText())
        assertEquals("Carte gelée : dégelez-la dans l'app pour payer", frozen["declineMessage"].asText())
        action(s, id, "unfreeze", mapOf("pin" to "2580")).andExpect { status { isOk() }; jsonPath("$.status") { value("ACTIVE") } }

        // Le lendemain, le plafond de la carte repart à zéro ; le solde, lui, limite.
        testClock.advance(Duration.ofDays(1))
        val s2 = login(s.phone)
        val broke = pay(id, 50_000)
        assertEquals("INSUFFICIENT_FUNDS", broke["declineCode"].asText())
        assertEquals(45_000, balance(s2))

        val list = mvc.get("/v1/me/cards/$id/authorizations") { header("Authorization", bearer(s2)) }.andExpect { status { isOk() } }.body()
        assertEquals(6, list.size())
        assertEquals(4, list.count { it["status"].asText() == "DECLINED" })
    }

    @Test
    fun `carte physique - commande, fabrication, agence, activation, paiement en magasin`() {
        val s = level2("+243970005005")
        topupConfirmed(s, 10_000, "top-1")
        val o = overview(s)
        assertEquals("Kolwezi - Agence principale", o["branches"][0].asText())
        assertEquals(listOf("Kolwezi - Agence principale", "Lubumbashi", "Kalemie", "Kinshasa"), o["branches"].map { it.asText() })
        assertEquals(100, o["physicalCardFeeMinor"].asLong())
        assertEquals(500, o["replacementFeeMinor"].asLong())

        orderPhysical(s, "cmd-1", branch = "Goma").andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_BRANCH") } }
        orderPhysical(s, "cmd-1", pin = "0000").andExpect { status { isUnauthorized() } }
        val card = orderPhysical(s, "cmd-1", branch = "kolwezi - agence principale").andExpect { status { isCreated() } }.body()
        val id = card["id"].asText()
        assertEquals("PHYSICAL", card["kind"].asText())
        assertEquals("ORDERED", card["status"].asText())
        assertEquals("Kolwezi - Agence principale", card["pickupBranch"].asText())
        assertFalse(card["onlinePayments"].asBoolean())
        // 1 $ pris à la commande.
        assertEquals(9_900, balance(s))
        // Commande renvoyée après une coupure : la même carte, sans seconds frais.
        assertEquals(id, orderPhysical(s, "cmd-1").andExpect { status { isCreated() } }.body()["id"].asText())
        orderPhysical(s, "cmd-2").andExpect { status { isConflict() }; jsonPath("$.code") { value("CARD_ALREADY_EXISTS") } }
        assertEquals(9_900, balance(s))

        // Pas encore là : ni activation ni paiement.
        action(s, id, "activate", mapOf("activationCode" to "000000", "cardPin" to "4826"))
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("CARD_NOT_READY") } }
        assertEquals("CARD_NOT_ACTIVE", pay(id, 100)["declineCode"].asText())

        assertEquals("IN_PRODUCTION", production(id)["status"].asText())
        assertEquals("READY_FOR_PICKUP", production(id)["status"].asText())
        val ready = notifications(s).first { it["kind"].asText() == "CARD_READY" }
        assertTrue(ready["body"].asText().contains("Kolwezi - Agence principale"))

        action(s, id, "activate", mapOf("activationCode" to "000000", "cardPin" to "4826"))
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_ACTIVATION_CODE") } }
        action(s, id, "activate", mapOf("activationCode" to activationCode(id), "cardPin" to "1234"))
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("PIN_TOO_SIMPLE") } }
        val active = action(s, id, "activate", mapOf("activationCode" to activationCode(id), "cardPin" to "4826"))
            .andExpect { status { isOk() } }.body()
        assertEquals("ACTIVE", active["status"].asText())

        // Paiements en ligne coupés par défaut sur la carte physique.
        assertEquals("ONLINE_PAYMENTS_DISABLED", pay(id, 1_000, online = true)["declineCode"].asText())
        assertEquals("APPROVED", pay(id, 1_000, merchant = "Pharmacie du Centre")["status"].asText())
        assertEquals(8_900, balance(s))
    }

    @Test
    fun `carte bloquee - definitif, remplacement a 5 dollars, nouveau numero qui repart de commandee`() {
        val s = level2("+243970005006")
        topupConfirmed(s, 10_000, "top-1")
        val id = orderPhysical(s, "cmd-1").andExpect { status { isCreated() } }.body()["id"].asText()
        production(id); production(id)
        action(s, id, "activate", mapOf("activationCode" to activationCode(id), "cardPin" to "4826")).andExpect { status { isOk() } }

        // Seule une carte bloquée se remplace.
        action(s, id, "replace", mapOf("pin" to "2580"), key = "rep-0")
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("CARD_NOT_REPLACEABLE") } }

        val blocked = action(s, id, "block", mapOf("reason" to "STOLEN", "pin" to "2580")).andExpect { status { isOk() } }.body()
        assertEquals("BLOCKED", blocked["status"].asText())
        assertEquals("STOLEN", blocked["blockReason"].asText())
        assertTrue(blocked["replaceable"].asBoolean())
        assertTrue(notifications(s).any { it["kind"].asText() == "CARD_BLOCKED" })
        assertEquals("CARD_BLOCKED", pay(id, 100)["declineCode"].asText())
        // Jamais de retour à l'état actif.
        action(s, id, "unfreeze", mapOf("pin" to "2580")).andExpect { status { isUnprocessableEntity() } }
        action(s, id, "freeze").andExpect { status { isUnprocessableEntity() } }

        val next = action(s, id, "replace", mapOf("pin" to "2580"), key = "rep-1").andExpect { status { isCreated() } }.body()
        assertEquals("ORDERED", next["status"].asText())
        assertEquals("Kolwezi - Agence principale", next["pickupBranch"].asText())
        assertEquals(9_400, balance(s))
        // Remplacement renvoyé : pas de seconde carte ni de seconds frais.
        assertEquals(next["id"].asText(), action(s, id, "replace", mapOf("pin" to "2580"), key = "rep-1").andExpect { status { isCreated() } }.body()["id"].asText())
        action(s, id, "replace", mapOf("pin" to "2580"), key = "rep-2").andExpect { status { isConflict() } }
        assertEquals(9_400, balance(s))
        val fee = history(s)[0]
        assertEquals("CARD_FEE", fee["kind"].asText())
        assertEquals(500, fee["amountMinor"].asLong())

        // L'écran Cartes montre la nouvelle carte, plus l'ancienne.
        val cards = overview(s)["cards"]
        assertEquals(1, cards.size())
        assertEquals(next["id"].asText(), cards[0]["id"].asText())
        assertFalse(cards[0]["last4"].isNull)
    }

    @Test
    fun `remplacement sans assez d'argent - rien n'est commande`() {
        val s = level2("+243970005007")
        topupConfirmed(s, 300, "top-1")
        val id = orderPhysical(s, "cmd-1").andExpect { status { isCreated() } }.body()["id"].asText()
        production(id); production(id)
        action(s, id, "block", mapOf("reason" to "LOST", "pin" to "2580")).andExpect { status { isOk() } }
        action(s, id, "replace", mapOf("pin" to "2580"), key = "rep-1")
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INSUFFICIENT_FUNDS") } }
        assertEquals(200, balance(s))
        assertEquals(1, jdbc.sql("SELECT count(*) FROM card").query(Int::class.java).single())
    }

    @Test
    fun `carte virtuelle supprimee puis recreee avec un autre numero`() {
        val s = level2("+243970005008")
        val first = createVirtual(s).andExpect { status { isCreated() } }.body()
        val id = first["id"].asText()
        action(s, id, "delete", mapOf("pin" to "2580")).andExpect { status { isNoContent() } }
        assertEquals(0, overview(s)["cards"].size())
        assertEquals("CARD_BLOCKED", pay(id, 100)["declineCode"].asText())
        mvc.get("/v1/me/cards/$id") { header("Authorization", bearer(s)) }.andExpect { status { isNotFound() } }

        val second = createVirtual(s).andExpect { status { isCreated() } }.body()
        assertTrue(second["id"].asText() != id)
    }

    @Test
    fun `carte expiree - refusee, remplacee par une nouvelle`() {
        val s = level2("+243970005011")
        topupConfirmed(s, 1_000, "top-1")
        val id = createVirtual(s).andExpect { status { isCreated() } }.body()["id"].asText()
        jdbc.sql("UPDATE card SET expiry_year = 2020 WHERE id = :id::uuid").param("id", id).update()
        assertEquals("CARD_EXPIRED", pay(id, 100)["declineCode"].asText())
        val seen = overview(s)["cards"][0]
        assertEquals("EXPIRED", seen["status"].asText())
        assertTrue(seen["replaceable"].asBoolean())
        val next = action(s, id, "replace", mapOf("pin" to "2580"), key = "rep-1").andExpect { status { isCreated() } }.body()
        assertEquals("ACTIVE", next["status"].asText())
        // Carte virtuelle : pas de frais.
        assertEquals(1_000, balance(s))
        assertEquals(listOf(next["id"].asText()), overview(s)["cards"].map { it["id"].asText() })
    }

    @Test
    fun `cartes d'un autre client et compte bloque`() {
        val a = level2("+243970005009")
        val b = level2("+243970005010", name = "Papy Ilunga")
        topupConfirmed(a, 5_000, "top-a")
        val id = createVirtual(a).andExpect { status { isCreated() } }.body()["id"].asText()
        mvc.get("/v1/me/cards/$id") { header("Authorization", bearer(b)) }.andExpect { status { isNotFound() } }
        action(b, id, "freeze").andExpect { status { isNotFound() } }
        action(b, id, "details", mapOf("pin" to "2580")).andExpect { status { isNotFound() } }

        jdbc.sql("UPDATE customer SET status = 'SUSPENDED' WHERE id = :id::uuid").param("id", a.customerId).update()
        assertEquals("ACCOUNT_NOT_ACTIVE", pay(id, 100)["declineCode"].asText())
        assertNull(jdbc.sql("SELECT transaction_id FROM card_authorization").query(String::class.java).list().firstOrNull())
    }
}
