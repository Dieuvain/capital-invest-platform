package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountClosureApiTest : ApiTest() {

    private fun check(s: Session) = mvc.get("/v1/me/closure") { header("Authorization", "Bearer ${s.token}") }.andExpect { status { isOk() } }.body()

    private fun close(s: Session, pin: String = "2580") = mvc.post("/v1/me/closure") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"pin":"$pin","reason":"OTHER_SERVICE","comment":"Je passe chez un autre service"}"""
    }

    private fun savings(s: Session, path: String, amountMinor: Long, key: String) = mvc.post("/v1/me/savings/$path") {
        header("Authorization", "Bearer ${s.token}")
        header("Idempotency-Key", key)
        contentType = MediaType.APPLICATION_JSON
        content = """{"amountMinor":$amountMinor}"""
    }

    @Test
    fun `fermer son compte une fois vide, envois programmes arretes, connexion refusee, numero garde`() {
        val alice = register("+243970001901")
        val bob = register("+243970001902")
        topupConfirmed(alice, 10_100, "t-1")
        savings(alice, "deposits", 1_000, "s-1").andExpect { status { is2xxSuccessful() } }
        mvc.post("/v1/me/scheduled-transfers") {
            header("Authorization", "Bearer ${alice.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"toPhone":"0970001902","amountMinor":1000,"frequency":"MONTHLY","day":5,"pin":"2580"}"""
        }.andExpect { status { isCreated() } }

        check(alice).let {
            assertEquals(false, it["canClose"].asBoolean())
            assertEquals(listOf("SAVINGS_NOT_EMPTY", "BALANCE_NOT_EMPTY"), it["blockers"].map { b -> b["code"].asText() })
            assertTrue(it["blockers"][0]["message"].asText().contains("10,00"))
        }
        close(alice).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("SAVINGS_NOT_EMPTY") } }

        savings(alice, "withdrawals", 1_000, "s-2").andExpect { status { is2xxSuccessful() } }
        transfer(alice, bob.phone, 10_000, "e-1").andExpect { status { isCreated() } }
        assertEquals(0, balance(alice))
        check(alice).let { assertEquals(true, it["canClose"].asBoolean()) }

        close(alice, pin = "1111").andExpect { status { isUnauthorized() } }
        close(alice).andExpect { status { isNoContent() } }

        mvc.get("/v1/me/balance") { header("Authorization", "Bearer ${alice.token}") }.andExpect { status { isUnauthorized() } }
        loginRequest(alice.phone, "2580").andExpect { status { isForbidden() }; jsonPath("$.code") { value("CUSTOMER_CLOSED") } }
        assertTrue(mvc.get("/v1/simulators/sms/${alice.phone}").body()["text"].asText().contains("ferme"))
        assertEquals(
            listOf("CANCELLED"),
            jdbc.sql("SELECT status FROM scheduled_transfer WHERE customer_id = :c::uuid").param("c", alice.customerId).query(String::class.java).list(),
        )
        // Plus personne ne peut lui envoyer d'argent, et le numéro ne sert pas à rouvrir un compte en ligne.
        transfer(login(bob.phone), alice.phone, 100, "e-2").andExpect { status { isUnprocessableEntity() } }
        val reason = jdbc.sql("SELECT closure_reason FROM customer WHERE id = :c::uuid").param("c", alice.customerId).query(String::class.java).single()
        assertEquals("OTHER_SERVICE : Je passe chez un autre service", reason)
        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_id = :c::uuid").param("c", alice.customerId).query(String::class.java).list()
        assertTrue("CUSTOMER_CLOSED" in audit)
    }
}
