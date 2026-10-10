package dev.axzy.stock.adapters.web.http

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingRequestValueException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.UUID

/**
 * What is not a use-case failure also answers with the error contract: a request
 * Spring could not read, a route that does not exist and, above all, an unexpected error.
 */
@RestControllerAdvice
class GlobalErrorHandler {

    private val log = LoggerFactory.getLogger(GlobalErrorHandler::class.java)

    @ExceptionHandler(FailureException::class)
    fun failure(e: FailureException): ResponseEntity<Any> = e.failure.toResponse()

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(): ResponseEntity<Any> =
        error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is not valid JSON or is missing fields.")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongParameterType(e: MethodArgumentTypeMismatchException): ResponseEntity<Any> {
        val isId = e.name.equals("id", ignoreCase = true) || e.name.endsWith("Id")
        return error(
            HttpStatus.BAD_REQUEST,
            if (isId) "INVALID_ID" else "INVALID_PARAMETER",
            "The value of '${e.name}' is not valid.",
            mapOf("parameter" to e.name),
        )
    }

    @ExceptionHandler(MissingRequestValueException::class)
    fun missingParameter(e: MissingRequestValueException): ResponseEntity<Any> =
        error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "A required request parameter is missing.")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(): ResponseEntity<Any> =
        error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "The method is not allowed on this route.")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedMediaType(): ResponseEntity<Any> =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "The content type must be application/json.")

    @ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
    fun notFound(): ResponseEntity<Any> = error(HttpStatus.NOT_FOUND, "NOT_FOUND", "The route does not exist.")

    /**
     * The file exceeds the container's limit.
     *
     * It is answered through the contract (`FILE_TOO_LARGE`) and **not** with the generic `500`:
     * the client has to be able to say "the file is too large" instead of
     * "internal error", which would also invite retrying the same file.
     */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun fileTooLarge(): ResponseEntity<Any> =
        error(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "The file exceeds the maximum allowed size.")

    /**
     * An error nobody expected. The client is NOT given the detail: an exception message
     * can carry SQL, paths or data. It is given an identifier to look up the incident in the
     * log, where it is recorded in full.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, request: HttpServletRequest): ResponseEntity<Any> {
        val incident = UUID.randomUUID().toString()
        log.error("Incident {} in {} {}", incident, request.method, request.requestURI, e)
        return error(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal error. The incident was recorded.",
            mapOf("incident" to incident),
        )
    }

    private fun error(status: HttpStatus, code: String, message: String, details: Map<String, String> = emptyMap()) =
        ResponseEntity.status(status).body<Any>(ErrorBody(code, message, details))
}
