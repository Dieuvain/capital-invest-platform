package cd.capitalinvest.platform.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Erreur métier : porte un code stable que l'app mobile peut traduire en fr, sw, ln. */
open class BusinessException(
    val code: String,
    message: String,
    val status: HttpStatus = HttpStatus.UNPROCESSABLE_ENTITY,
) : RuntimeException(message)

class NotFoundException(code: String, message: String) : BusinessException(code, message, HttpStatus.NOT_FOUND)

class ConflictException(code: String, message: String) : BusinessException(code, message, HttpStatus.CONFLICT)

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(BusinessException::class)
    fun business(e: BusinessException): ProblemDetail = problem(e.status, e.code, e.message ?: e.code)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ProblemDetail {
        val detail = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail)
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "En-tête obligatoire absent : ${e.headerName}")

    private fun problem(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("https://api.capitalinvest.cd/erreurs/${code.lowercase()}")
            title = code
            setProperty("code", code)
        }
}
