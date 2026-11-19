package cd.capitalinvest.platform

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalLimitApiTest : ApiTest() {

    private fun setLimit(s: Session, minor: Long?, pin: String = "2580") = mvc.put("/v1/me/limits/daily") {
        header("Authorization", "Bearer ${s.token}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"dailyOutMinor":${minor ?: "null"},"pin":"$pin"}"""
    }

    private fun limits(s: Session) = mvc.get("/v1/me/limits") { header("Authorization", "Bearer ${s.token}") }.body()

    private fun sms(phone: String) = mvc.get("/v1/simulators/sms/$phone").body()["text"].asText()

    @Test
    fun `baisser son plafond est immediat, le remonter attend 24 h et previent le client par SMS`() {
        val alice = register("+243970001801")
        val bob = register("+243970001802")
        for (i in 1..3) topupConfirmed(alice, 20_000, "t-$i")

        setLimit(alice, 5_000, pin = "1111").andExpect { status { isUnauthorized() } }
        setLimit(alice, 5_000).andExpect { status { isOk() } }.body().let {
            assertEquals(5_000, it["dailyOutMinor"].asLong())
            assertEquals(5_000, it["personalDailyOutMinor"].asLong())
            assertEquals(60_000, it["levelDailyOutMinor"].asLong())
            assertTrue(it["pendingDailyOut"].isNull)
        }
        transfer(alice, bob.phone, 4_000, "e-1").andExpect { status { isCreated() } }
        transfer(alice, bob.phone, 2_000, "e-2")
            .andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("LIMIT_DAILY_PERSONAL") } }

        // Remonter : rien ne change avant 24 h, et le client est prévenu.
        val raised = setLimit(alice, 30_000).andExpect { status { isOk() } }.body()
        assertEquals(5_000, raised["dailyOutMinor"].asLong())
        assertEquals(30_000, raised["pendingDailyOut"]["dailyOutMinor"].asLong())
        val at = OffsetDateTime.parse(raised["pendingDailyOut"]["effectiveAt"].asText())
        assertTrue(Duration.between(OffsetDateTime.now(), at).toHours() in 23..24)
        assertTrue(sms(alice.phone).contains("300,00"))
        transfer(alice, bob.phone, 2_000, "e-3").andExpect { jsonPath("$.code") { value("LIMIT_DAILY_PERSONAL") } }

        // Le vrai titulaire annule la hausse, sans PIN.
        mvc.delete("/v1/me/limits/daily/pending") { header("Authorization", "Bearer ${alice.token}") }
            .andExpect { status { isOk() } }.body().let { assertTrue(it["pendingDailyOut"].isNull) }

        setLimit(alice, 30_000).andExpect { status { isOk() } }
        testClock.advance(Duration.ofHours(25))
        val again = login(alice.phone)
        limits(again).let {
            assertEquals(30_000, it["dailyOutMinor"].asLong())
            assertEquals(30_000, it["personalDailyOutMinor"].asLong())
            assertTrue(it["pendingDailyOut"].isNull)
        }
        transfer(again, bob.phone, 20_000, "e-4").andExpect { status { isCreated() } }

        // Revenir au plafond du niveau, c'est aussi une hausse ; baisser ensuite annule la hausse en attente.
        setLimit(again, null).andExpect { status { isOk() } }.body().let {
            assertTrue(it["pendingDailyOut"]["dailyOutMinor"].isNull)
            assertEquals(30_000, it["personalDailyOutMinor"].asLong())
        }
        setLimit(again, 10_000).andExpect { status { isOk() } }.body().let {
            assertEquals(10_000, it["personalDailyOutMinor"].asLong())
            assertTrue(it["pendingDailyOut"].isNull)
        }

        setLimit(again, 70_000).andExpect { jsonPath("$.code") { value("INVALID_DAILY_LIMIT") } }
        setLimit(again, 500).andExpect { jsonPath("$.code") { value("INVALID_DAILY_LIMIT") } }
        val audit = jdbc.sql("SELECT action FROM audit_log WHERE target_id = :c::uuid").param("c", alice.customerId).query(String::class.java).list()
        assertTrue(audit.containsAll(listOf("DAILY_LIMIT_LOWERED", "DAILY_LIMIT_RAISE_REQUESTED", "DAILY_LIMIT_RAISE_CANCELLED")))
    }
}
