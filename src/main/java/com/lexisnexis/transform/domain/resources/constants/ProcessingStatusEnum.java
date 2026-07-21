package com.lexisnexis.transform.domain.resources.constants;

/**
 * Represents the lifecycle stages of a document submission.
 *
 * Flow: PENDING → PROCESSING → PUBLISHED (success)
 *                            → INVALID   (XSD validation failed)
 *                            → FAILED    (unexpected error)
 */
public enum ProcessingStatusEnum {

    /** Document has been received and is waiting to be picked up by a worker thread. */
    PENDING,

    /** A worker thread is actively validating and transforming the document. */
    PROCESSING,

    /** Transformation succeeded; JSON and plain-text artifacts are on disk. */
    PUBLISHED,

    /** XML did not pass XSD validation; diagnostics are available on the record. */
    INVALID,

    /** An unexpected error occurred during processing. */
    FAILED
}
