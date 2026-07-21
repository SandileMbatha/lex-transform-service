package com.lexisnexis.transform.domain.service;

import com.lexisnexis.transform.config.AppProperties;
import com.lexisnexis.transform.domain.exception.ArtifactStorageException;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes document artifacts (JSON and plain text) to the local filesystem.
 *
 * Directory layout per document:
 *
 *   {outputDir}/
 *     {content_id}/
 *       judgment.json   (normalised JSON record, produced by XSLT)
 *       judgment.txt    (plain text of all paragraphs, for AI/RAG)
 *
 * Writing is idempotent: re-publishing the same content_id overwrites the
 * existing files rather than creating duplicates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPublishingService {

    private static final String LOG_PREFIX = "[Document Publishing] - ";
    private static final String JSON_ARTIFACT_FILENAME = "judgment.json";
    private static final String PLAIN_TEXT_ARTIFACT_FILENAME = "judgment.txt";

    private final AppProperties appProperties;

    private Path artifactOutputRootDirectory;

    /**
     * Resolves the output root directory from config and creates it if it does not exist.
     * Called once automatically by Spring after dependency injection.
     *
     * @throws IOException if the directory cannot be created
     */
    @PostConstruct
    void initialiseOutputDirectory() throws IOException {
        artifactOutputRootDirectory = Path.of(appProperties.getOutputDir()).toAbsolutePath().normalize();
        Files.createDirectories(artifactOutputRootDirectory);
        log.info("{}Artifact output directory initialised: {}", LOG_PREFIX, artifactOutputRootDirectory);
    }

    /**
     * Writes the JSON and plain-text artifacts for a document to disk.
     *
     * @param contentId    the document's unique identifier (used as the directory name)
     * @param jsonContent  the normalised JSON string from the XSLT transformation
     * @param plainText    the concatenated paragraph text for RAG ingestion
     * @return             paths to the written files (stored on the DocumentRecord)
     */
    public PublishedArtifactPaths publishDocumentArtifacts(final String contentId,
                                                           final String jsonContent,
                                                           final String plainText) {
        final Path documentOutputDirectory = artifactOutputRootDirectory.resolve(sanitiseForFilesystem(contentId));
        try {
            Files.createDirectories(documentOutputDirectory);

            final Path jsonArtifactPath = documentOutputDirectory.resolve(JSON_ARTIFACT_FILENAME);
            final Path plainTextArtifactPath = documentOutputDirectory.resolve(PLAIN_TEXT_ARTIFACT_FILENAME);

            writeToFile(jsonArtifactPath, jsonContent);
            writeToFile(plainTextArtifactPath, plainText);

            log.info("{}Artifacts published for content_id={} at path={}",
                    LOG_PREFIX, contentId, documentOutputDirectory);

            return PublishedArtifactPaths.builder()
                    .jsonArtifactPath(jsonArtifactPath.toString())
                    .plainTextArtifactPath(plainTextArtifactPath.toString())
                    .build();

        } catch (final IOException ioException) {
            throw new ArtifactStorageException(
                    "Failed to write artifacts for content_id=" + contentId, ioException);
        }
    }

    /**
     * Reads the previously published JSON artifact for the given document.
     *
     * @param contentId the document's unique identifier
     * @return the full contents of {@code output/{contentId}/judgment.json}
     * @throws IOException if the file does not exist or cannot be read
     */
    public String readPublishedJsonArtifact(final String contentId) throws IOException {
        return Files.readString(
                artifactOutputRootDirectory.resolve(sanitiseForFilesystem(contentId)).resolve(JSON_ARTIFACT_FILENAME));
    }

    /**
     * Reads the previously published plain-text artifact for the given document.
     *
     * @param contentId the document's unique identifier
     * @return the full contents of {@code output/{contentId}/judgment.txt}
     * @throws IOException if the file does not exist or cannot be read
     */
    public String readPublishedPlainTextArtifact(final String contentId) throws IOException {
        return Files.readString(
                artifactOutputRootDirectory.resolve(sanitiseForFilesystem(contentId)).resolve(PLAIN_TEXT_ARTIFACT_FILENAME));
    }

    /**
     * Writes {@code fileContent} to {@code filePath}, creating or overwriting the file atomically.
     *
     * @param filePath    the target path; its parent directory must already exist
     * @param fileContent the UTF-8 content to write
     * @throws IOException if the write fails
     */
    private void writeToFile(final Path filePath, final String fileContent) throws IOException {
        Files.writeString(filePath, fileContent, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Replaces characters that are unsafe in filesystem paths with underscores.
     * Allows alphanumerics, dots, underscores, and hyphens; replaces everything else.
     *
     * @param contentId the raw content identifier (may contain slashes, spaces, etc.)
     * @return a filesystem-safe directory name derived from the content identifier
     */
    private String sanitiseForFilesystem(final String contentId) {
        return contentId.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    /**
     * Holds the absolute filesystem paths of the two artifact files written by a single publish operation.
     * Use {@link #builder()} to construct instances.
     */
    @Getter
    @Builder
    public static class PublishedArtifactPaths {
        /** Absolute path of the written {@code judgment.json} file. */
        private final String jsonArtifactPath;
        /** Absolute path of the written {@code judgment.txt} file. */
        private final String plainTextArtifactPath;
    }
}
