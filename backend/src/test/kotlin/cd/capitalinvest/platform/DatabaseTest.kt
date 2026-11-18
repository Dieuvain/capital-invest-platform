package cd.capitalinvest.platform

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient

/** Base des tests d'intégration : base PostgreSQL réelle, vidée avant chaque test (comptes système conservés). */
@SpringBootTest
abstract class DatabaseTest {
    @Autowired
    lateinit var jdbc: JdbcClient

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("TRUNCATE topup, ledger_entry, ledger_transaction").update()
        jdbc.sql("DELETE FROM ledger_account WHERE customer_id IS NOT NULL").update()
        jdbc.sql("DELETE FROM customer").update()
    }
}
