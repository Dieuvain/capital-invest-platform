package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavingsApiTest : ApiTest() {

    private fun move(s: Session, path: String, amountMinor: Long, key: String) =
        mvc.post("/v1/me/savings/$path") {
            header("Authorization", "Bearer ${s.token}")
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"amountMinor":$amountMinor}"""
        }

    @Test
    fun `mettre de cote, reprendre, objectif, sans frais ni notification`() {
        val alice = register("+243970001001")
        mvc.get("/v1/me/savings") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.balanceMinor") { value(0) }
                jsonPath("$.moves.length()") { value(0) }
            }
        topupConfirmed(alice, 15_000, "t-1")

        val saved = move(alice, "deposits", 10_000, "s-1").andExpect { status { isOk() } }.body()
        assertEquals(10_000, saved["balanceMinor"].asLong())
        assertEquals(5_000, saved["walletBalanceMinor"].asLong())
        assertEquals(10_000, saved["savedThisMonthMinor"].asLong())
        assertEquals(5_000, balance(alice))
        // Même clé : rien ne bouge une seconde fois.
        move(alice, "deposits", 10_000, "s-1").andExpect { status { isOk() } }
        assertEquals(5_000, balance(alice))

        move(alice, "deposits", 6_000, "s-2").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("INSUFFICIENT_FUNDS") }
        }
        move(alice, "withdrawals", 12_000, "r-1").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("SAVINGS_INSUFFICIENT") }
        }
        val back = move(alice, "withdrawals", 2_500, "r-2").andExpect { status { isOk() } }.body()
        assertEquals(7_500, back["balanceMinor"].asLong())
        assertEquals(listOf("WITHDRAWAL", "DEPOSIT"), back["moves"].map { it["kind"].asText() })
        assertEquals(7_500, balance(alice))

        val goal = mvc.put("/v1/me/savings/goal") {
            header("Authorization", "Bearer ${alice.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"label":"  Rentrée scolaire ","targetMinor":30000}"""
        }.andExpect { status { isOk() } }.body()["goal"]
        assertEquals("Rentrée scolaire", goal["label"].asText())
        assertEquals(25, goal["progressPercent"].asInt())
        assertEquals(22_500, goal["remainingMinor"].asLong())
        mvc.delete("/v1/me/savings/goal") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() }; jsonPath("$.goal") { doesNotExist() } }

        // L'historique du portefeuille montre les deux mouvements, sans frais ; aucune notification n'est envoyée.
        val items = mvc.get("/v1/me/history") { header("Authorization", "Bearer ${alice.token}") }.body()["items"]
        assertEquals(listOf("SAVINGS_WITHDRAWAL", "SAVINGS_DEPOSIT", "TOPUP"), items.map { it["kind"].asText() })
        assertEquals(-10_000, items[1]["netMinor"].asLong())
        assertEquals(0, items[1]["feeMinor"].asLong())
        val filtered = mvc.get("/v1/me/history?category=SAVINGS") { header("Authorization", "Bearer ${alice.token}") }.body()["items"]
        assertEquals(2, filtered.size())
        val notified = jdbc.sql(
            """SELECT count(*) FROM notification n JOIN ledger_transaction t ON t.id = n.transaction_id
               WHERE t.type LIKE 'SAVINGS_%'""",
        ).query(Long::class.java).single()
        assertEquals(0, notified)
    }

    @Test
    fun `l'epargne compte dans le solde maximal du niveau`() {
        val bob = register("+243970001002")
        val carol = register("+243970001003")
        // Niveau 1 : 1 000 $ au plus, portefeuille et épargne ensemble.
        repeat(4) { topupConfirmed(bob, 20_000, "t-$it") }
        move(bob, "deposits", 70_000, "s-1").andExpect { status { isOk() } }
        topupConfirmed(bob, 15_000, "t-4") // 95 000 au total
        mvc.post("/v1/topups") {
            header("Authorization", "Bearer ${bob.token}")
            header("Idempotency-Key", "t-5")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operator":"MPESA","amountMinor":10000}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("LIMIT_MAX_BALANCE") }
        }
        topupConfirmed(carol, 10_000, "c-1")
        transfer(carol, bob.phone, 6_000, "c-2").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("RECIPIENT_LIMIT_REACHED") }
        }
        transfer(carol, bob.phone, 5_000, "c-3").andExpect { status { isCreated() } }
        assertTrue(balance(bob) < 100_000)
    }
}
