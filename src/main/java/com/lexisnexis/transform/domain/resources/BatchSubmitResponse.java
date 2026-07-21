package com.lexisnexis.transform.domain.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response returned by the batch endpoints after queuing multiple documents.
 *
 * <p>Returned immediately with HTTP 202 Accepted. Each document in
 * {@code acceptedContentIds} will be processed asynchronously — poll
 * {@code GET /api/v1/documents/{contentId}} per ID to track progress.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchSubmitResponse {
    /** Number of documents successfully accepted for processing. */
    private int submittedCount;
    /** Content IDs of all accepted documents, in submission order. */
    private List<String> acceptedContentIds;
    private String message;
}
