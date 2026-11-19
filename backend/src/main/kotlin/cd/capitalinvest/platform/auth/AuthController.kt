package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.customers.Language
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

private const val RDC_PHONE = "^\\+243[0-9]{9}$"
private const val RDC_PHONE_MESSAGE = "numéro congolais attendu, format +243XXXXXXXXX"

data class OtpRequest(@field:Pattern(regexp = RDC_PHONE, message = RDC_PHONE_MESSAGE) val phone: String)

data class RegisterRequest(
    @field:Pattern(regexp = RDC_PHONE, message = RDC_PHONE_MESSAGE) val phone: String,
    @field:Pattern(regexp = "^[0-9]{6}$", message = "code à 6 chiffres") val code: String,
    @field:NotBlank @field:Size(max = 120) val fullName: String,
    val language: Language = Language.fr,
    val pin: String,
    @field:NotBlank @field:Size(max = 100) val deviceId: String,
    /** Nom lisible du téléphone (« Samsung Galaxy A14 »), montré au client dans ses connexions. */
    @field:Size(max = 80) val deviceName: String? = null,
)

data class LoginRequest(
    @field:Pattern(regexp = RDC_PHONE, message = RDC_PHONE_MESSAGE) val phone: String,
    val pin: String,
    @field:NotBlank @field:Size(max = 100) val deviceId: String,
    /** Nom lisible du téléphone (« Samsung Galaxy A14 »), montré au client dans ses connexions. */
    @field:Size(max = 80) val deviceName: String? = null,
)

data class BlockRequest(
    @field:Pattern(regexp = RDC_PHONE, message = RDC_PHONE_MESSAGE) val phone: String,
    val pin: String,
)

data class PinResetRequest(
    @field:Pattern(regexp = RDC_PHONE, message = RDC_PHONE_MESSAGE) val phone: String,
    @field:Pattern(regexp = "^[0-9]{6}$", message = "code à 6 chiffres") val code: String,
    val newPin: String,
    @field:NotBlank @field:Size(max = 100) val deviceId: String,
    /** Nom lisible du téléphone (« Samsung Galaxy A14 »), montré au client dans ses connexions. */
    @field:Size(max = 80) val deviceName: String? = null,
)

@RestController
@RequestMapping("/v1/auth")
class AuthController(private val otp: OtpService, private val auth: AuthService) {

    @PostMapping("/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestOtp(@Valid @RequestBody body: OtpRequest) = otp.request(body.phone, OtpPurpose.REGISTER)

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody b: RegisterRequest): SessionTokens =
        auth.register(b.phone, b.code, b.fullName, b.language, b.pin, b.deviceId, b.deviceName)

    @PostMapping("/login")
    fun login(@Valid @RequestBody b: LoginRequest): SessionTokens = auth.login(b.phone, b.pin, b.deviceId, b.deviceName)

    @PostMapping("/pin-reset/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPinReset(@Valid @RequestBody body: OtpRequest) = auth.requestPinReset(body.phone)

    @PostMapping("/pin-reset")
    fun resetPin(@Valid @RequestBody b: PinResetRequest): SessionTokens = auth.resetPin(b.phone, b.code, b.newPin, b.deviceId, b.deviceName)

    /** Téléphone perdu ou volé : bloquer son compte depuis un autre téléphone, sans s'y connecter. */
    @PostMapping("/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(@Valid @RequestBody b: BlockRequest) = auth.blockWithPhone(b.phone, b.pin)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestHeader("Authorization") header: String) = auth.logout(header.removePrefix("Bearer ").trim())
}

data class SimulatedSms(val phone: String, val text: String?)

/** Lecture du dernier SMS simulé, pour tester l'inscription sans opérateur. Désactivé en production. */
@RestController
@RequestMapping("/v1/simulators/sms")
@ConditionalOnProperty("capital-invest.simulators-enabled", havingValue = "true")
class SmsSimulatorController(private val sms: SimulatedSmsGateway) {
    @GetMapping("/{phone}")
    fun last(@PathVariable phone: String) = SimulatedSms(phone, sms.lastMessage(phone))
}
