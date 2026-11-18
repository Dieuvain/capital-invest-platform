package cd.capitalinvest.platform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import java.time.Clock
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Application {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
