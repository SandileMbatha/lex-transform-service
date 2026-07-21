package com.lexisnexis.transform.domain.model;

import com.lexisnexis.transform.domain.resources.constants.ProcessingStatusEnum;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Tracks the full lifecycle of a single submitted document.
 *
 * Immutable fields (final): set once on creation, never change.
 * Mutable fields (volatile): updated by background worker threads.
 *
 * The volatile keyword ensures that status updates made by a worker
 * thread are immediately visible to the HTTP threads reading the status.
 *
 * State transitions are only allowed via the mark* methods to prevent
 * invalid status changes (e.g. going from PUBLISHED back to PENDING).
 */
@Getter
public class DocumentRecord {

    private final String contentId;
    private final String contentHash;
    private final Instant submittedAt;

    private volatile ProcessingStatusEnum processingStatus;
    private volatile Instant processedAt;
    private volatile List<String> validationErrors;
    private volatile String publishedJsonArtifactPath;
    private volatile String publishedPlainTextArtifactPath;
    private volatile String failureMessage;

    /**
     * Creates a new record in the {@link ProcessingStatusEnum#PENDING} state.
     *
     * @param contentId   unique identifier extracted from the document's {@code <content_id>} element
     * @param contentHash SHA-256 hex digest of the raw XML bytes, used for idempotency checks
     */
    public DocumentRecord(final String contentId, final String contentHash) {
        this.contentId = contentId;
        this.contentHash = contentHash;
        this.submittedAt = Instant.now();
        this.processingStatus = ProcessingStatusEnum.PENDING;
    }

    /** Transitions status to {@link ProcessingStatusEnum#PROCESSING}. Called at pipeline start. */
    public void markAsProcessing() {
        this.processingStatus = ProcessingStatusEnum.PROCESSING;
    }

    /**
     * Transitions status to {@link ProcessingStatusEnum#PUBLISHED} and records artifact paths.
     *
     * @param jsonArtifactPath      absolute path of the written {@code judgment.json} file
     * @param plainTextArtifactPath absolute path of the written {@code judgment.txt} file
     */
    public void markAsPublished(final String jsonArtifactPath, final String plainTextArtifactPath) {
        this.publishedJsonArtifactPath = jsonArtifactPath;
        this.publishedPlainTextArtifactPath = plainTextArtifactPath;
        this.processedAt = Instant.now();
        this.processingStatus = ProcessingStatusEnum.PUBLISHED;
    }

    /**
     * Transitions status to {@link ProcessingStatusEnum#INVALID} and stores the diagnostics.
     *
     * @param xsdValidationErrors all constraint violations reported by the XSD validator,
     *                            formatted as {@code "SEVERITY [line X, col Y]: message"}
     */
    public void markAsInvalid(final List<String> xsdValidationErrors) {
        this.validationErrors = List.copyOf(xsdValidationErrors);
        this.processedAt = Instant.now();
        this.processingStatus = ProcessingStatusEnum.INVALID;
    }

    /**
     * Transitions status to {@link ProcessingStatusEnum#FAILED} and stores the error message.
     *
     * @param errorMessage description of the unexpected error that caused the pipeline to abort
     */
    public void markAsFailed(final String errorMessage) {
        this.failureMessage = errorMessage;
        this.processedAt = Instant.now();
        this.processingStatus = ProcessingStatusEnum.FAILED;
    }
}
