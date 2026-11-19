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
    private val allByPhone = ConcurrentHashMap<String, MutableList<String>>()

    /** Pour les tests : nombre d'envois à faire échouer, comme une passerelle indisponible. */
    @Volatile var failuresToSimulate = 0

    override fun send(phone: String, text: String) {
        if (failuresToSimulate > 0) {
            failuresToSimulate--
            throw IllegalStateException("Passerelle SMS indisponible (simulation)")
        }
        lastByPhone[phone] = text
        allByPhone.computeIfAbsent(phone) { java.util.Collections.synchronizedList(mutableListOf()) }.add(text)
        // Le contenu n'est pas journalisé : il peut contenir un code de sécurité.
        log.info("SMS simulé envoyé à {}", phone.take(7) + "*****")
    }

    fun lastMessage(phone: String): String? = lastByPhone[phone]

    fun messages(phone: String): List<String> = allByPhone[phone]?.toList() ?: emptyList()

    fun clear() {
        lastByPhone.clear()
        allByPhone.clear()
        failuresToSimulate = 0
    }
}
