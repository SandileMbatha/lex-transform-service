package com.lexisnexis.transform.domain.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Thrown by {@link com.lexisnexis.transform.domain.service.DocumentValidationService}
 * when an XML document fails XSD schema validation.
 *
 * <p>The full list of constraint violations is carried on the exception so the
 * orchestrator can persist them on the {@link com.lexisnexis.transform.domain.model.DocumentRecord}
 * and return them to callers polling the status endpoint.</p>
 *
 * <p>Maps to HTTP 422 Unprocessable Entity with error code {@code VALIDATION_FAILED}.</p>
 */
@Getter
public class DocumentValidationException extends DocumentProcessingException {

    private final List<String> validationErrors;

    /**
     * @param validationErrors all XSD constraint violations found in the document,
     *                         formatted as {@code "SEVERITY [line X, col Y]: message"}
     */
    public DocumentValidationException(final List<String> validationErrors) {
        super("VALIDATION_FAILED",
                "Document validation failed with " + validationErrors.size() + " error(s)",
                HttpStatus.UNPROCESSABLE_ENTITY);
        this.validationErrors = validationErrors;
    }
}
