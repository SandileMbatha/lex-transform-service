package com.lexisnexis.transform.domain.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all document processing failures.
 *
 * Subclasses set a specific HTTP status and error code so the
 * GlobalExceptionHandler can return the right response without
 * needing a chain of instanceof checks.
 */
@Getter
public class DocumentProcessingException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    /**
     * @param errorCode    machine-readable code included in the error response body
     * @param errorMessage human-readable message included in the error response body
     * @param httpStatus   HTTP status code this exception maps to
     */
    public DocumentProcessingException(final String errorCode,
                                       final String errorMessage,
                                       final HttpStatus httpStatus) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * @param errorCode    machine-readable code included in the error response body
     * @param errorMessage human-readable message included in the error response body
     * @param httpStatus   HTTP status code this exception maps to
     * @param cause        the underlying exception that triggered this failure
     */
    public DocumentProcessingException(final String errorCode,
                                       final String errorMessage,
                                       final HttpStatus httpStatus,
                                       final Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
