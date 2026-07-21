package com.lexisnexis.transform.domain.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lexisnexis.transform.domain.resources.constants.ProcessingStatusEnum;
import com.lexisnexis.transform.domain.model.DocumentRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response returned by GET /api/v1/documents/{contentId}.
 *
 * Fields present per status:
 *   PENDING / PROCESSING — only contentId, status, submittedAt
 *   PUBLISHED            — above + processedAt + artifacts (json + plainText)
 *   INVALID              — above (no artifacts) + validationErrors
 *   FAILED               — above (no artifacts) + failureMessage
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentStatusResponse {

    private String contentId;
    private ProcessingStatusEnum status;
    private Instant submittedAt;
    private Instant processedAt;
    private List<String> validationErrors;
    private String failureMessage;
    private Artifacts artifacts;

    /**
     * Maps a {@link DocumentRecord} to a status response, optionally including artifact content.
     *
     * <p>Pass non-null {@code jsonContent} and {@code plainText} only when the record is
     * {@link ProcessingStatusEnum#PUBLISHED} and the artifacts have been read from disk.
     * Null values are excluded from the JSON output via {@code @JsonInclude(NON_NULL)}.</p>
     *
     * @param documentRecord the source record to map
     * @param jsonContent    the contents of {@code judgment.json}, or {@code null} if not yet published
     * @param plainText      the contents of {@code judgment.txt}, or {@code null} if not yet published
     * @return a fully populated status response
     */
    public static DocumentStatusResponse fromDocumentRecord(final DocumentRecord documentRecord,
                                                             final String jsonContent,
                                                             final String plainText) {
        final Artifacts artifactsDto = (jsonContent != null)
                ? Artifacts.builder()
                        .jsonContent(jsonContent)
                        .plainText(plainText)
                        .build()
                : null;

        return DocumentStatusResponse.builder()
                .contentId(documentRecord.getContentId())
                .status(documentRecord.getProcessingStatus())
                .submittedAt(documentRecord.getSubmittedAt())
                .processedAt(documentRecord.getProcessedAt())
                .validationErrors(documentRecord.getValidationErrors())
                .failureMessage(documentRecord.getFailureMessage())
                .artifacts(artifactsDto)
                .build();
    }

    /** The JSON and plain-text artifacts produced once a document is published. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Artifacts {
        private String jsonContent;
        private String plainText;
    }
}
