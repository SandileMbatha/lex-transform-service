package com.lexisnexis.transform.domain.exception;

import com.lexisnexis.transform.domain.resources.error.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralised exception handler for all controllers.
 *
 * Converts domain exceptions into consistent HTTP error responses so that
 * callers always receive the same JSON error shape regardless of which
 * exception was thrown internally.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles all {@link DocumentProcessingException} subclasses
     * ({@link DocumentValidationException}, {@link DocumentTransformException}, {@link ArtifactStorageException}).
     *
     * <p>Each subclass carries its own HTTP status and error code, so a single handler covers all of
     * them without {@code instanceof} checks.</p>
     *
     * @param documentProcessingException the domain exception carrying status code and error code
     * @return a response with the exception's HTTP status and a uniform {@link ErrorResponse} body
     */
    @ExceptionHandler(DocumentProcessingException.class)
    public ResponseEntity<ErrorResponse> handleDocumentProcessingException(
            final DocumentProcessingException documentProcessingException) {
        log.warn("Document processing error: code={}, message={}",
                documentProcessingException.getErrorCode(), documentProcessingException.getMessage());
        return ResponseEntity
                .status(documentProcessingException.getHttpStatus())
                .body(buildErrorResponse(documentProcessingException.getErrorCode(),
                        documentProcessingException.getMessage()));
    }

    /**
     * Catch-all for any exception not handled by a more specific {@link ExceptionHandler}.
     *
     * @param unexpectedException the unhandled exception
     * @return HTTP 500 Internal Server Error with a generic {@link ErrorResponse} body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(final Exception unexpectedException) {
        log.error("Unexpected error: ", unexpectedException);
        return ResponseEntity.internalServerError()
                .body(buildErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    /**
     * Assembles a uniform {@link ErrorResponse} from the given code and message.
     *
     * @param errorCode    machine-readable constant (e.g. {@code VALIDATION_FAILED})
     * @param errorMessage human-readable description safe to surface to API callers
     * @return a fully populated {@link ErrorResponse}
     */
    private ErrorResponse buildErrorResponse(final String errorCode, final String errorMessage) {
        return ErrorResponse.builder()
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
