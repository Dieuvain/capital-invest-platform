package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

class HistorySearchApiTest : ApiTest() {

    private fun search(s: Session, q: String, extra: String = "") =
        mvc.get("/v1/me/history?x=1$extra") {
            header("Authorization", "Bearer ${s.token}")
            param("q", q)
        }

    private fun kinds(s: Session, q: String, extra: String = "") =
        search(s, q, extra).andExpect { status { isOk() } }.body()["items"].map { "${it["kind"].asText()}:${it["amountMinor"].asLong()}" }

    @Test
    fun `recherche par motif, nom, numero, montant et numero de recu`() {
        val alice = register("+243970004101")
        val bob = register("+243970004102")
        register("+243970004103")
        topupConfirmed(alice, 20_000, "t1")
        transfer(alice, bob.phone, 2_500, "a").andExpect { status { isCreated() } }
        mvc.post("/v1/transfers") {
            header("Authorization", "Bearer ${alice.token}")
            header("Idempotency-Key", "b")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"toPhone":"+243970004103","amountMinor":1250,"pin":"2580","note":"Loyer de novembre"}"""
        }.andExpect { status { isCreated() } }

        assertEquals(listOf("TRANSFER_SENT:1250"), kinds(alice, "loyer"))
        assertEquals(listOf("TRANSFER_SENT:1250"), kinds(alice, "  LOYER   de  "))
        // Sans accents ni majuscules : « novembré » mal tapé, « Grâce » tapé « grace ».
        jdbc.sql("UPDATE customer SET full_name = 'Grâce Mbuyi' WHERE phone = '+243970004103'").update()
        assertEquals(listOf("TRANSFER_SENT:1250"), kinds(alice, "grace mbuyi"))
        assertEquals(listOf("TRANSFER_SENT:1250"), kinds(alice, "GRÂCE"))
        assertEquals(listOf("TRANSFER_SENT:2500"), kinds(alice, "client +243970004102"))
        // Le numéro, tapé comme on le dicte.
        assertEquals(listOf("TRANSFER_SENT:2500"), kinds(alice, "0970 004 102"))
        assertEquals(listOf("TRANSFER_SENT:2500"), kinds(alice, "25"))
        assertEquals(listOf("TRANSFER_SENT:1250"), kinds(alice, "12,50"))
        assertEquals(2, kinds(alice, "970004").size)

        val topup = mvc.get("/v1/me/history?category=TOPUP") { header("Authorization", "Bearer ${alice.token}") }.body()["items"][0]
        assertEquals(listOf("TOPUP:20000"), kinds(alice, topup["receiptNumber"].asText().lowercase()))

        // Les filtres se combinent, et les caractères spéciaux sont cherchés tels quels.
        assertEquals(emptyList(), kinds(alice, "loyer", "&category=TOPUP"))
        assertEquals(emptyList(), kinds(alice, "%"))
        assertEquals(emptyList(), kinds(alice, "zzz"))
        assertEquals(3, kinds(alice, "   ").size)

        // Chez le destinataire, l'expéditeur se retrouve par son numéro.
        assertEquals(listOf("TRANSFER_RECEIVED:2500"), kinds(bob, "970004101"))

        search(alice, "x".repeat(61)).andExpect { status { isUnprocessableEntity() } }
            .andExpect { jsonPath("$.code") { value("INVALID_SEARCH") } }
    }
}
