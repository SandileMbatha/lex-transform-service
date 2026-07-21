package com.lexisnexis.transform.controller;

import com.lexisnexis.transform.domain.resources.constants.ProcessingStatusEnum;
import com.lexisnexis.transform.domain.model.DocumentRecord;
import com.lexisnexis.transform.domain.service.DocumentOrchestratorService;
import com.lexisnexis.transform.domain.service.DocumentPublishingService;
import com.lexisnexis.transform.domain.resources.BatchFolderRequest;
import com.lexisnexis.transform.domain.resources.BatchSubmitResponse;
import com.lexisnexis.transform.domain.resources.DocumentStatusResponse;
import com.lexisnexis.transform.domain.resources.DocumentSubmitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller that exposes all document processing endpoints.
 *
 * This controller is intentionally thin — it handles only HTTP concerns
 * (request parsing, status codes, response building) and delegates all
 * business logic to DocumentOrchestratorService.
 *
 * Endpoints:
 *   POST /api/v1/documents                  — submit one XML document
 *   GET  /api/v1/documents/{contentId}      — get status + artifacts
 *   GET  /api/v1/documents                  — list all submitted documents
 *   POST /api/v1/documents/batch            — upload multiple XML files
 *   POST /api/v1/documents/batch/folder     — process a server-side folder
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentOrchestratorService documentOrchestratorService;
    private final DocumentPublishingService documentPublishingService;

    /**
     * Accepts one XML legal judgment and queues it for asynchronous processing.
     *
     * <p>The orchestrator extracts {@code <content_id>} and computes a SHA-256 hash
     * before registering the document. The response is returned immediately — poll
     * {@link #retrieveDocumentStatus} to check when processing is done.</p>
     *
     * @param xmlDocumentContent the raw XML body; must conform to {@code urn:lex:content:1}
     * @return 202 Accepted when newly queued, 200 OK when identical content was already published
     */
    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<DocumentSubmitResponse> submitDocument(@RequestBody final String xmlDocumentContent) {
        log.info("Received document submission request");

        final DocumentRecord documentRecord =
                documentOrchestratorService.submitDocumentForProcessing(xmlDocumentContent);

        final boolean isAlreadyPublished =
                documentRecord.getProcessingStatus() == ProcessingStatusEnum.PUBLISHED;

        if (isAlreadyPublished) {
            log.info("Duplicate submission for content_id={} — returning existing published record",
                    documentRecord.getContentId());
            return ResponseEntity.ok(DocumentSubmitResponse.fromDuplicateDocument(documentRecord));
        }

        log.info("Document accepted for processing: content_id={}", documentRecord.getContentId());
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(DocumentSubmitResponse.fromAcceptedDocument(documentRecord));
    }


    /**
     * Returns the current processing status and, once complete, the published artifacts.
     *
     * @param contentId the document's unique identifier from its {@code <content_id>} element
     * @return 200 OK with the full status record, or 404 if {@code contentId} is unknown
     */
    @GetMapping("/{contentId}")
    public ResponseEntity<DocumentStatusResponse> retrieveDocumentStatus(
            @PathVariable final String contentId) {
        log.debug("Retrieving status for content_id={}", contentId);

        return documentOrchestratorService.findDocumentByContentId(contentId)
                .map(documentRecord -> {
                    String jsonContent = null;
                    String plainTextContent = null;

                    if (documentRecord.getProcessingStatus() == ProcessingStatusEnum.PUBLISHED) {
                        try {
                            jsonContent = documentPublishingService.readPublishedJsonArtifact(contentId);
                            plainTextContent = documentPublishingService.readPublishedPlainTextArtifact(contentId);
                        } catch (final IOException artifactReadException) {
                            log.warn("Could not read artifacts for content_id={}: {}",
                                    contentId, artifactReadException.getMessage());
                        }
                    }

                    return ResponseEntity.ok(
                            DocumentStatusResponse.fromDocumentRecord(documentRecord, jsonContent, plainTextContent));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns a summary of all submitted documents. Artifact content is not included. */
    @GetMapping
    public List<DocumentStatusResponse> listAllDocuments() {
        log.debug("Listing all submitted documents");
        return documentOrchestratorService.retrieveAllDocuments().stream()
                .map(documentRecord -> DocumentStatusResponse.fromDocumentRecord(documentRecord, null, null))
                .collect(Collectors.toList());
    }


    /**
     * Accepts multiple XML files as multipart form data and queues each for asynchronous processing.
     *
     * <p>Files that cannot be read are logged and skipped; the remaining files are still accepted.</p>
     *
     * @param uploadedFiles one or more XML judgment files
     * @return 202 Accepted with the count and content IDs of accepted documents
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchSubmitResponse> submitDocumentBatch(
            @RequestPart("files") final List<MultipartFile> uploadedFiles) {
        log.info("Received batch submission with {} file(s)", uploadedFiles.size());

        final List<String> acceptedContentIds = new ArrayList<>();
        for (final MultipartFile uploadedFile : uploadedFiles) {
            try {
                final String xmlDocumentContent = new String(uploadedFile.getBytes(), StandardCharsets.UTF_8);
                final DocumentRecord documentRecord =
                        documentOrchestratorService.submitDocumentForProcessing(xmlDocumentContent);
                acceptedContentIds.add(documentRecord.getContentId());
            } catch (final IOException fileReadException) {
                log.error("Failed to read uploaded file={}: {}", uploadedFile.getOriginalFilename(),
                        fileReadException.getMessage());
            }
        }

        return ResponseEntity.accepted().body(BatchSubmitResponse.builder()
                .submittedCount(acceptedContentIds.size())
                .acceptedContentIds(acceptedContentIds)
                .message("Batch accepted — %d document(s) queued for processing"
                        .formatted(acceptedContentIds.size()))
                .build());
    }

    /**
     * Scans a server-side directory for {@code .xml} files and queues each for processing.
     *
     * <p>Only the top-level files in the directory are scanned (depth 1 — no recursion).</p>
     *
     * @param batchFolderRequest contains the absolute path of the directory to scan
     * @return 202 Accepted with the count and content IDs, or 400 if the path is not a directory
     */
    @PostMapping("/batch/folder")
    public ResponseEntity<BatchSubmitResponse> submitDocumentFolder(
            @RequestBody final BatchFolderRequest batchFolderRequest) throws IOException {
        log.info("Received folder batch request for path={}", batchFolderRequest.getFolderPath());

        final Path folderPath = Path.of(batchFolderRequest.getFolderPath());
        if (!Files.isDirectory(folderPath)) {
            return ResponseEntity.badRequest().body(BatchSubmitResponse.builder()
                    .submittedCount(0)
                    .acceptedContentIds(List.of())
                    .message("Provided path is not a directory: " + batchFolderRequest.getFolderPath())
                    .build());
        }

        final List<String> acceptedContentIds = new ArrayList<>();
        try (final var xmlFilesStream = Files.walk(folderPath, 1)) {
            xmlFilesStream
                    .filter(filePath -> filePath.toString().endsWith(".xml"))
                    .forEach(xmlFilePath -> {
                        try {
                            final String xmlDocumentContent = Files.readString(xmlFilePath, StandardCharsets.UTF_8);
                            final DocumentRecord documentRecord =
                                    documentOrchestratorService.submitDocumentForProcessing(xmlDocumentContent);
                            acceptedContentIds.add(documentRecord.getContentId());
                        } catch (final IOException fileReadException) {
                            log.error("Failed to read XML file={}: {}", xmlFilePath, fileReadException.getMessage());
                        }
                    });
        }

        return ResponseEntity.accepted().body(BatchSubmitResponse.builder()
                .submittedCount(acceptedContentIds.size())
                .acceptedContentIds(acceptedContentIds)
                .message("Folder batch accepted — %d document(s) queued for processing"
                        .formatted(acceptedContentIds.size()))
                .build());
    }
}
