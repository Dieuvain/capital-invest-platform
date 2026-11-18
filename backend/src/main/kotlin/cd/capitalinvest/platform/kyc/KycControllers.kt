package cd.capitalinvest.platform.kyc

import cd.capitalinvest.platform.auth.CUSTOMER_ID_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AGENT_ATTRIBUTE
import cd.capitalinvest.platform.backoffice.AgentPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.util.UUID

data class KycSubmitRequest(
    val idType: IdType,
    @field:NotBlank @field:Size(max = 30) @field:Pattern(regexp = "^[A-Za-z0-9 -]+$", message = "lettres et chiffres seulement")
    val idNumber: String,
    val birthDate: LocalDate,
)

data class RejectRequest(@field:NotBlank @field:Size(max = 300) val reason: String)

@RestController
@RequestMapping("/v1/me/kyc")
class MyKycController(private val kyc: KycService) {

    @GetMapping
    fun status(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID): MyKycView = kyc.mine(customerId)

    /** Envoi d'une photo (multipart, champ « file »). La dernière photo de chaque type est celle retenue. */
    @PostMapping("/documents/{kind}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID,
        @PathVariable kind: DocumentKind,
        @RequestParam("file") file: MultipartFile,
    ): KycDocumentView = kyc.upload(customerId, kind, file.contentType, file.bytes)

    @PostMapping("/submission")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@RequestAttribute(CUSTOMER_ID_ATTRIBUTE) customerId: UUID, @Valid @RequestBody b: KycSubmitRequest): MyKycView =
        kyc.submit(customerId, b.idType, b.idNumber, b.birthDate)
}

@RestController
@RequestMapping("/v1/backoffice/kyc")
class BackofficeKycController(private val kyc: KycService) {

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "PENDING") status: KycStatus,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<KycRequestView> = kyc.list(status, limit.coerceIn(1, 200))

    @GetMapping("/{id}")
    fun get(@RequestAttribute(AGENT_ATTRIBUTE) agent: AgentPrincipal, @PathVariable id: UUID) = kyc.get(agent, id)

    @GetMapping("/{id}/documents/{documentId}")
    fun document(
        @RequestAttribute(AGENT_ATTRIBUTE) agent: AgentPrincipal,
        @PathVariable id: UUID,
        @PathVariable documentId: UUID,
    ): ResponseEntity<ByteArray> {
        val doc = kyc.document(agent, id, documentId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(doc.contentType))
            .cacheControl(CacheControl.noStore()) // une pièce d'identité ne doit rester dans aucun cache
            .body(doc.bytes)
    }

    @PostMapping("/{id}/approve")
    fun approve(@RequestAttribute(AGENT_ATTRIBUTE) agent: AgentPrincipal, @PathVariable id: UUID) = kyc.approve(agent, id)

    @PostMapping("/{id}/reject")
    fun reject(@RequestAttribute(AGENT_ATTRIBUTE) agent: AgentPrincipal, @PathVariable id: UUID, @Valid @RequestBody b: RejectRequest) =
        kyc.reject(agent, id, b.reason)
}
