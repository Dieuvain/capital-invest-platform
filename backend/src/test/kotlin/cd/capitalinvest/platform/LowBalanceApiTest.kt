package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LowBalanceApiTest : ApiTest() {

    private fun setThreshold(s: Session, minor: Long?) = mvc.put("/v1/me/notifications/low-balance") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"thresholdMinor":${minor ?: "null"}}"""
    }

    private fun alerts(s: Session) = mvc.get("/v1/me/notifications") { header("Authorization", "Bearer ${s.token}") }
        .body()["items"].filter { it["kind"].asText() == "LOW_BALANCE" }

    @Test
    fun `alerte une fois en passant sous le seuil, puis de nouveau apres etre repasse au-dessus`() {
        val alice = register("+243970002001")
        val bob = register("+243970002002")
        topupConfirmed(alice, 10_000, "t-1")
        setThreshold(alice, 2_000).andExpect { status { isOk() }; jsonPath("$.thresholdMinor") { value(2_000) } }

        transfer(alice, bob.phone, 5_000, "e-1").andExpect { status { isCreated() } } // reste 49,50 $
        assertEquals(0, alerts(alice).size)
        transfer(alice, bob.phone, 3_000, "e-2").andExpect { status { isCreated() } } // reste 19,20 $ : alerte
        transfer(alice, bob.phone, 1_000, "e-3").andExpect { status { isCreated() } } // toujours dessous : rien
        val first = alerts(alice)
        assertEquals(1, first.size)
        assertTrue(first[0]["body"].asText().contains("19,20"))
        val sms = jdbc.sql("SELECT sms_text FROM notification WHERE customer_id = :c::uuid AND kind = 'LOW_BALANCE'")
            .param("c", alice.customerId).query(String::class.java).single()
        assertEquals("Capital Invest: votre solde est de 19,20 $, sous votre seuil de 20,00 $.", sms)
        // Bob, qui n'a pas choisi de seuil, n'est jamais prévenu.
        assertEquals(0, alerts(bob).size)

        topupConfirmed(alice, 5_000, "t-2") // repasse au-dessus : l'alerte se réarme
        transfer(alice, bob.phone, 5_000, "e-4").andExpect { status { isCreated() } }
        assertEquals(2, alerts(alice).size)

        mvc.get("/v1/me/notifications/low-balance") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { jsonPath("$.thresholdMinor") { value(2_000) } }
        setThreshold(alice, 50).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("INVALID_THRESHOLD") } }
        setThreshold(alice, null).andExpect { status { isOk() } }
        topupConfirmed(alice, 5_000, "t-3")
        transfer(alice, bob.phone, 5_000, "e-5").andExpect { status { isCreated() } }
        assertEquals(2, alerts(alice).size)
    }
}
