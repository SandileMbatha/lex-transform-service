package com.lexisnexis.transform.domain.resources;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v1/documents/batch/folder}.
 *
 * <p>The service scans {@code folderPath} for {@code .xml} files (top level only,
 * no recursion) and submits each one as an individual document.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchFolderRequest {
    /** Absolute path to the directory containing XML judgment files. */
    private String folderPath;
}
