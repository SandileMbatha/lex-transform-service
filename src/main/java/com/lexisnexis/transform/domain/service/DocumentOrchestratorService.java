package com.lexisnexis.transform.domain.service;

import com.lexisnexis.transform.domain.resources.constants.ProcessingStatusEnum;
import com.lexisnexis.transform.domain.exception.DocumentValidationException;
import com.lexisnexis.transform.domain.model.DocumentRecord;
import com.lexisnexis.transform.domain.service.DocumentPublishingService.PublishedArtifactPaths;
import com.lexisnexis.transform.metrics.DocumentTransformMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Orchestrates the four-stage document processing pipeline.
 *
 * This is the main entry point for all document submissions. It:
 * <ol>
 *   <li>Checks for duplicate submissions (same content_id + same content hash)</li>
 *   <li>Registers a new DocumentRecord in the in-memory index</li>
 *   <li>Submits the document to the background thread pool for async processing</li>
 * </ol>
 *
 * The background pipeline (run by a worker thread):
 * <ol>
 *   <li>VALIDATE  — throws DocumentValidationException if XSD check fails</li>
 *   <li>TRANSFORM — run the XSLT to produce JSON; extract plain text</li>
 *   <li>PUBLISH   — write both artifacts to disk</li>
 *   <li>RECORD    — update the DocumentRecord status so callers can poll</li>
 * </ol>
 *
 * Idempotency:
 *   Each document is keyed by content_id + SHA-256 hash of the XML bytes.
 *   Re-submitting identical content returns the existing published record.
 */
@Slf4j
@Service
public class DocumentOrchestratorService {

    private static final String LOG_PREFIX = "[Document Orchestrator] - ";

    private final DocumentValidationService documentValidationService;
    private final DocumentTransformationService documentTransformationService;
    private final DocumentPublishingService documentPublishingService;
    private final DocumentTransformMetrics documentTransformMetrics;
    private final Executor documentProcessingExecutor;

    private final ConcurrentHashMap<String, DocumentRecord> documentRecordIndex = new ConcurrentHashMap<>();

    /**
     * @param documentValidationService    validates XML against the XSD schema
     * @param documentTransformationService runs the XSLT stylesheet and extracts plain text
     * @param documentPublishingService    writes both artifacts to the output directory
     * @param documentTransformMetrics     records processing counters and timers
     * @param documentProcessingExecutor   bounded thread pool for background pipeline execution
     */
    public DocumentOrchestratorService(
            final DocumentValidationService documentValidationService,
            final DocumentTransformationService documentTransformationService,
            final DocumentPublishingService documentPublishingService,
            final DocumentTransformMetrics documentTransformMetrics,
            @Qualifier("documentProcessingExecutor") final Executor documentProcessingExecutor) {
        this.documentValidationService = documentValidationService;
        this.documentTransformationService = documentTransformationService;
        this.documentPublishingService = documentPublishingService;
        this.documentTransformMetrics = documentTransformMetrics;
        this.documentProcessingExecutor = documentProcessingExecutor;
    }

    /**
     * Accepts an XML document and enqueues it for asynchronous validation and transformation.
     *
     * <p>Returns immediately with a {@link DocumentRecord} in {@link ProcessingStatusEnum#PENDING} state.
     * Poll {@link #findDocumentByContentId} to track progress to {@code PUBLISHED}, {@code INVALID},
     * or {@code FAILED}.</p>
     *
     * <p>If an identical document (same {@code content_id} and same SHA-256 content hash) was already
     * {@code PUBLISHED}, the existing record is returned without re-processing.</p>
     *
     * @param xmlDocumentContent the raw XML body of the legal judgment
     * @return a {@link DocumentRecord} tracking this submission's lifecycle
     */
    public DocumentRecord submitDocumentForProcessing(final String xmlDocumentContent) {
        final String contentHash = computeSha256Hash(xmlDocumentContent);
        final String contentId = extractContentIdFromXml(xmlDocumentContent);

        final DocumentRecord existingRecord = documentRecordIndex.get(contentId);
        if (isDuplicateSubmission(existingRecord, contentHash)) {
            log.debug("{}Duplicate submission detected for content_id={}", LOG_PREFIX, contentId);
            documentTransformMetrics.incrementDuplicateSubmissionCount();
            return existingRecord;
        }

        final DocumentRecord newDocumentRecord = new DocumentRecord(contentId, contentHash);
        documentRecordIndex.put(contentId, newDocumentRecord);
        documentTransformMetrics.incrementSubmittedDocumentCount();

        log.info("{}Document accepted for processing: content_id={}", LOG_PREFIX, contentId);
        CompletableFuture.runAsync(
                () -> orchestrateDocumentPipeline(newDocumentRecord, xmlDocumentContent),
                documentProcessingExecutor);

        return newDocumentRecord;
    }

    /**
     * Looks up a previously submitted document by its {@code content_id}.
     *
     * @param contentId the unique identifier from the document's {@code <content_id>} element
     * @return the matching {@link DocumentRecord}, or empty if this ID has not been submitted
     */
    public Optional<DocumentRecord> findDocumentByContentId(final String contentId) {
        return Optional.ofNullable(documentRecordIndex.get(contentId));
    }

    /**
     * Returns all documents submitted since the service started.
     *
     * @return a live, unordered view of every {@link DocumentRecord} in the in-memory index
     */
    public Collection<DocumentRecord> retrieveAllDocuments() {
        return documentRecordIndex.values();
    }

    /**
     * Executes all four pipeline stages on a worker thread.
     *
     * <p>{@link DocumentValidationException} is caught separately from other exceptions so that
     * XSD violations result in {@link ProcessingStatusEnum#INVALID} (caller error) rather than
     * {@link ProcessingStatusEnum#FAILED} (service error).</p>
     *
     * @param documentRecord     the record whose status is updated as each stage completes
     * @param xmlDocumentContent the raw XML content to validate, transform, and publish
     */
    private void orchestrateDocumentPipeline(final DocumentRecord documentRecord,
                                              final String xmlDocumentContent) {
        documentRecord.markAsProcessing();
        final long pipelineStartNanos = System.nanoTime();

        try {
            documentValidationService.validateXmlAgainstSchema(xmlDocumentContent);

            final String normalisedJsonContent =
                    documentTransformationService.transformXmlDocumentToJson(xmlDocumentContent);
            final String plainTextContent =
                    documentTransformationService.extractPlainTextFromXmlDocument(xmlDocumentContent);

            final PublishedArtifactPaths publishedArtifactPaths =
                    documentPublishingService.publishDocumentArtifacts(
                            documentRecord.getContentId(), normalisedJsonContent, plainTextContent);

            documentRecord.markAsPublished(
                    publishedArtifactPaths.getJsonArtifactPath(),
                    publishedArtifactPaths.getPlainTextArtifactPath());

            documentTransformMetrics.incrementPublishedDocumentCount();
            documentTransformMetrics.recordTransformationDuration(System.nanoTime() - pipelineStartNanos);
            log.info("{}Document processing completed: content_id={}", LOG_PREFIX, documentRecord.getContentId());

        } catch (final DocumentValidationException validationException) {
            documentRecord.markAsInvalid(validationException.getValidationErrors());
            documentTransformMetrics.incrementInvalidDocumentCount();
            log.warn("{}Document failed XSD validation: content_id={}, errors={}",
                    LOG_PREFIX, documentRecord.getContentId(), validationException.getValidationErrors());

        } catch (final Exception unexpectedException) {
            documentRecord.markAsFailed(unexpectedException.getMessage());
            documentTransformMetrics.incrementFailedDocumentCount();
            log.error("{}Document processing failed: content_id={}, error={}",
                    LOG_PREFIX, documentRecord.getContentId(), unexpectedException.getMessage(),
                    unexpectedException);
        }
    }

    /**
     * Returns {@code true} when the submission is an identical re-send of an already-published document.
     *
     * <p>Requires both the same {@code content_id} (covered by the map lookup) AND the same
     * SHA-256 hash so that a corrected document with the same ID is treated as a new submission.</p>
     *
     * @param existingRecord the record currently in the index, or {@code null} if none exists
     * @param contentHash    SHA-256 hex digest of the incoming XML bytes
     * @return {@code true} if the document is already published with identical content
     */
    private boolean isDuplicateSubmission(final DocumentRecord existingRecord, final String contentHash) {
        return existingRecord != null
                && existingRecord.getProcessingStatus() == ProcessingStatusEnum.PUBLISHED
                && contentHash.equals(existingRecord.getContentHash());
    }

    /**
     * Computes the SHA-256 hex digest of {@code inputContent} encoded as UTF-8 bytes.
     *
     * @param inputContent the string to hash
     * @return lowercase 64-character hex string of the 256-bit digest
     * @throws IllegalStateException if the JVM does not provide SHA-256 (should never happen)
     */
    private static String computeSha256Hash(final String inputContent) {
        try {
            final byte[] hashBytes = MessageDigest.getInstance("SHA-256")
                    .digest(inputContent.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (final NoSuchAlgorithmException algorithmException) {
            throw new IllegalStateException("SHA-256 algorithm not available", algorithmException);
        }
    }

    /**
     * Extracts the {@code <content_id>} value from raw XML text without a full DOM parse.
     *
     * <p>Uses a simple substring search to avoid the overhead of a second XML parse before
     * the document reaches the validation stage. Falls back to a hash-based fallback ID
     * if the element is absent, which will still let the pipeline report an INVALID result
     * rather than crashing.</p>
     *
     * @param xmlDocumentContent the raw XML string
     * @return the trimmed value of {@code <content_id>}, or {@code "unknown-{first8hexChars}"} if absent
     */
    static String extractContentIdFromXml(final String xmlDocumentContent) {
        final int openTagIndex = xmlDocumentContent.indexOf("<content_id>");
        final int closeTagIndex = xmlDocumentContent.indexOf("</content_id>");
        if (openTagIndex != -1 && closeTagIndex != -1 && closeTagIndex > openTagIndex) {
            return xmlDocumentContent.substring(openTagIndex + "<content_id>".length(), closeTagIndex).strip();
        }
        return "unknown-" + computeSha256Hash(xmlDocumentContent).substring(0, 8);
    }
}
