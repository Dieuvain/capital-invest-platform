package cd.capitalinvest.platform.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/** Envoi de SMS. La passerelle réelle (opérateur ou agrégateur) remplacera le simulateur. */
interface SmsGateway {
    fun send(phone: String, text: String)
}

@Service
class SimulatedSmsGateway : SmsGateway {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastByPhone = ConcurrentHashMap<String, String>()

    override fun send(phone: String, text: String) {
        lastByPhone[phone] = text
        // Le contenu n'est pas journalisé : il peut contenir un code de sécurité.
        log.info("SMS simulé envoyé à {}", phone.take(7) + "*****")
    }

    fun lastMessage(phone: String): String? = lastByPhone[phone]
}
