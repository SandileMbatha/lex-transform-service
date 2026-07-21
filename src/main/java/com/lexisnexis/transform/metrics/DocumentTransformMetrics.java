package com.lexisnexis.transform.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Registers and exposes Micrometer metrics for document processing operations.
 *
 * Metrics are exposed at /actuator/prometheus and can be scraped by Prometheus
 * for dashboarding in Grafana.
 *
 * Available metrics:
 *   documents_submitted_total       — every document received
 *   documents_published_total       — every successful transform + publish
 *   documents_invalid_total         — every XSD validation failure
 *   documents_failed_total          — every unexpected processing error
 *   documents_duplicate_total       — every re-submission skipped as duplicate
 *   documents_transform_duration    — histogram of end-to-end processing time
 */
@Component
@RequiredArgsConstructor
public class DocumentTransformMetrics {

    private final MeterRegistry meterRegistry;

    private Counter submittedDocumentCounter;
    private Counter publishedDocumentCounter;
    private Counter invalidDocumentCounter;
    private Counter failedDocumentCounter;
    private Counter duplicateSubmissionCounter;
    private Timer transformationDurationTimer;

    /**
     * Registers all counters and timers with the {@link MeterRegistry}.
     * Called once automatically by Spring after dependency injection.
     */
    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        submittedDocumentCounter = Counter.builder("documents.submitted.total")
                .description("Total documents submitted for processing")
                .register(meterRegistry);

        publishedDocumentCounter = Counter.builder("documents.published.total")
                .description("Total documents successfully transformed and published")
                .register(meterRegistry);

        invalidDocumentCounter = Counter.builder("documents.invalid.total")
                .description("Total documents that failed XSD validation")
                .register(meterRegistry);

        failedDocumentCounter = Counter.builder("documents.failed.total")
                .description("Total documents that failed due to an unexpected processing error")
                .register(meterRegistry);

        duplicateSubmissionCounter = Counter.builder("documents.duplicate.total")
                .description("Total re-submissions skipped because the same content was already published")
                .register(meterRegistry);

        transformationDurationTimer = Timer.builder("documents.transform.duration")
                .description("End-to-end processing time per document (validate + transform + publish)")
                .register(meterRegistry);
    }

    /** Increments {@code documents_submitted_total} — called once per new unique submission. */
    public void incrementSubmittedDocumentCount() {
        submittedDocumentCounter.increment();
    }

    /** Increments {@code documents_published_total} — called when the full pipeline succeeds. */
    public void incrementPublishedDocumentCount() {
        publishedDocumentCounter.increment();
    }

    /** Increments {@code documents_invalid_total} — called when XSD validation fails. */
    public void incrementInvalidDocumentCount() {
        invalidDocumentCounter.increment();
    }

    /** Increments {@code documents_failed_total} — called on unexpected transform or I/O errors. */
    public void incrementFailedDocumentCount() {
        failedDocumentCounter.increment();
    }

    /** Increments {@code documents_duplicate_total} — called when an identical document is re-submitted. */
    public void incrementDuplicateSubmissionCount() {
        duplicateSubmissionCounter.increment();
    }

    /**
     * Records the end-to-end pipeline duration for {@code documents_transform_duration}.
     *
     * @param durationNanos elapsed time in nanoseconds from pipeline start to successful publish
     */
    public void recordTransformationDuration(final long durationNanos) {
        transformationDurationTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
