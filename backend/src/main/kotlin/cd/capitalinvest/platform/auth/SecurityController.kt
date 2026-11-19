package cd.capitalinvest.platform.auth

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ChangePinRequest(val currentPin: String, val newPin: String)

data class SignOutResult(val closed: Int)

/** Écran « Sécurité » de l'app : changer son PIN, voir ses connexions récentes, fermer les autres. */
@RestController
@RequestMapping("/v1/me/security")
class SecurityController(private val auth: AuthService) {

    @PostMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePin(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @RequestHeader("Authorization") header: String,
        @RequestBody b: ChangePinRequest,
    ) = auth.changePin(customerId, token(header), b.currentPin, b.newPin)

    @GetMapping("/sessions")
    fun sessions(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestHeader("Authorization") header: String) =
        auth.sessions(customerId, token(header))

    @PostMapping("/sessions/sign-out-others")
    fun signOutOthers(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @RequestHeader("Authorization") header: String) =
        SignOutResult(auth.signOutOthers(customerId, token(header)))

    private fun token(header: String) = header.removePrefix("Bearer ").trim()
}
