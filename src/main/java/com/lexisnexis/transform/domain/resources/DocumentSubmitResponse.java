package com.lexisnexis.transform.domain.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lexisnexis.transform.domain.resources.constants.ProcessingStatusEnum;
import com.lexisnexis.transform.domain.model.DocumentRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response returned immediately after a document is submitted.
 *
 * HTTP 202 Accepted  — document is queued for processing
 * HTTP 200 OK        — same document was already published (duplicate, no-op)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentSubmitResponse {

    private String contentId;
    private ProcessingStatusEnum status;
    private Instant submittedAt;
    private String message;

    /**
     * Builds the response for a document that has been newly queued (HTTP 202 Accepted).
     *
     * @param documentRecord the freshly created record in {@link ProcessingStatusEnum#PENDING} state
     * @return a response indicating the document was accepted for processing
     */
    public static DocumentSubmitResponse fromAcceptedDocument(final DocumentRecord documentRecord) {
        return DocumentSubmitResponse.builder()
                .contentId(documentRecord.getContentId())
                .status(documentRecord.getProcessingStatus())
                .submittedAt(documentRecord.getSubmittedAt())
                .message("Document accepted for processing")
                .build();
    }

    /**
     * Builds the response for an idempotent re-submission of an already-published document (HTTP 200 OK).
     *
     * @param documentRecord the existing record in {@link ProcessingStatusEnum#PUBLISHED} state
     * @return a response indicating no reprocessing was needed
     */
    public static DocumentSubmitResponse fromDuplicateDocument(final DocumentRecord documentRecord) {
        return DocumentSubmitResponse.builder()
                .contentId(documentRecord.getContentId())
                .status(documentRecord.getProcessingStatus())
                .submittedAt(documentRecord.getSubmittedAt())
                .message("Already published with identical content — no reprocessing needed")
                .build();
    }
}
