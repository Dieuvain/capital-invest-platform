package cd.capitalinvest.platform

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.jdbc.core.simple.JdbcClient

/** Base des tests d'intégration : base PostgreSQL réelle, vidée avant chaque test (comptes système conservés). */
@SpringBootTest
@Import(DatabaseTest.TestClockConfig::class)
abstract class DatabaseTest {
    @TestConfiguration
    class TestClockConfig {
        @Bean
        @Primary
        fun mutableClock() = MutableClock()
    }

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var testClock: MutableClock

    @BeforeEach
    fun cleanDatabase() {
        testClock.reset()
        jdbc.sql("TRUNCATE notification, session, biometric_key, otp_challenge, mobile_money_event, reconciliation_item, reconciliation_run, withdrawal, topup, ledger_entry, ledger_transaction, kyc_request, kyc_document, agent_session, backoffice_agent, audit_log, promotion, merchant_payment, merchant, suspicion_report, monitoring_alert, money_request, savings_goal, airtime_purchase, bill_payment, complaint, scheduled_transfer, recipient_lookup, favorite_recipient, pending_transfer, pot_contribution, pot").update()
        jdbc.sql("DELETE FROM ledger_account WHERE customer_id IS NOT NULL OR type = 'MERCHANT_WALLET'").update()
        jdbc.sql("DELETE FROM customer").update()
    }
}

/** Horloge que les tests peuvent avancer, pour vérifier les expirations. */
class MutableClock : Clock() {
    @Volatile private var offset: Duration = Duration.ZERO
    fun advance(d: Duration) { offset = offset.plus(d) }
    fun reset() { offset = Duration.ZERO }
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.now().plus(offset)
}
