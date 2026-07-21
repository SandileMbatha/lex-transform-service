package com.lexisnexis.transform.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link com.lexisnexis.transform.domain.service.DocumentPublishingService}
 * when writing or reading an artifact file from the output directory fails.
 *
 * <p>Maps to HTTP 500 Internal Server Error with error code {@code ARTIFACT_STORAGE_FAILED}.</p>
 */
public class ArtifactStorageException extends DocumentProcessingException {

    /**
     * @param errorMessage description including the {@code content_id} that failed
     * @param cause        the underlying {@link java.io.IOException}
     */
    public ArtifactStorageException(final String errorMessage, final Throwable cause) {
        super("ARTIFACT_STORAGE_FAILED", errorMessage, HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }
}
