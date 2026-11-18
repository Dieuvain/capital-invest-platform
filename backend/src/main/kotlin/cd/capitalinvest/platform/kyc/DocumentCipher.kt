package cd.capitalinvest.platform.kyc

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("capital-invest.kyc")
data class KycProperties(
    /** Clé AES-256 en base64 (32 octets). A fournir par variable d'environnement en production. */
    val encryptionKey: String,
    val maxFileBytes: Int = 5 * 1024 * 1024,
    val minimumAge: Int = 18,
    /** Noms de la liste de sanctions simulée, en attendant le fournisseur réel. */
    val simulatedWatchlist: List<String> = emptyList(),
)

/** Chiffrement des pièces d'identité au repos : AES-256-GCM, IV aléatoire de 12 octets en tête. */
@Component
class DocumentCipher(props: KycProperties) {
    private val key = SecretKeySpec(
        Base64.getDecoder().decode(props.encryptionKey).also {
            require(it.size == 32) { "capital-invest.kyc.encryption-key doit faire 32 octets en base64" }
        },
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored, 0, 12)) }
        return cipher.doFinal(stored, 12, stored.size - 12)
    }
}
