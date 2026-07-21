package com.lexisnexis.transform.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link com.lexisnexis.transform.domain.service.DocumentTransformationService}
 * when Saxon fails to run the XSLT stylesheet or execute an XPath expression.
 *
 * <p>Maps to HTTP 500 Internal Server Error with error code {@code TRANSFORM_FAILED}.</p>
 */
public class DocumentTransformException extends DocumentProcessingException {

    /**
     * @param errorMessage description of the Saxon failure
     * @param cause        the underlying {@link net.sf.saxon.s9api.SaxonApiException}
     */
    public DocumentTransformException(final String errorMessage, final Throwable cause) {
        super("TRANSFORM_FAILED", errorMessage, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }
}
