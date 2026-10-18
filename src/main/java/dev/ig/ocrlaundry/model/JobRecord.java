package dev.ig.ocrlaundry.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import dev.ig.ocrlaundry.ocr.OcrResult;

public final class JobRecord {
    private final UUID id;
    private final JobSpec spec;
    private final DocumentInfo document;
    private final long submittedSequence;
    private final Instant submittedAt;
    private final int estimatedCost;

    private JobStatus status = JobStatus.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private String resultText = "";
    private String errorMessage = "";

    public JobRecord(
            UUID id,
            JobSpec spec,
            DocumentInfo document,
            long submittedSequence,
            Instant submittedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.document = Objects.requireNonNull(document, "document");
        if (submittedSequence < 0) {
            throw new IllegalArgumentException("submittedSequence cannot be negative");
        }
        this.submittedSequence = submittedSequence;
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.estimatedCost = document.pageCount();
    }

    public long submittedSequence() {
        return submittedSequence;
    }

    public UUID id() {
        return id;
    }

    public DocumentInfo document() {
        return document;
    }

    public JobStatus status() {
        return status;
    }

    public void markRunning(Instant time) {
        requireStatus(JobStatus.QUEUED);
        startedAt = notBefore(time, submittedAt);
        status = JobStatus.RUNNING;
    }

    public void markFinished(OcrResult result, Instant time) {
        requireStatus(JobStatus.RUNNING);
        Objects.requireNonNull(result, "result");
        finishedAt = notBefore(time, startedAt);
        resultText = result.text();
        errorMessage = result.errorMessage();
        status = result.successful() ? JobStatus.COMPLETED : JobStatus.FAILED;
    }

    public void markFailed(String message, Instant time) {
        requireStatus(JobStatus.RUNNING);
        finishedAt = notBefore(time, startedAt);
        errorMessage = Objects.requireNonNull(message, "message");
        status = JobStatus.FAILED;
    }

    public void markCancelled(Instant time) {
        if (status != JobStatus.QUEUED && status != JobStatus.RUNNING) {
            throw new IllegalStateException("Cannot cancel job from " + status);
        }
        Instant lowerBound = startedAt == null ? submittedAt : startedAt;
        finishedAt = notBefore(time, lowerBound);
        status = JobStatus.CANCELLED;
    }

    public JobSnapshot snapshot() {
        return new JobSnapshot(
                id,
                spec.documentId(),
                spec.priority(),
                submittedSequence,
                submittedAt,
                startedAt,
                finishedAt,
                status,
                document.pageCount(),
                estimatedCost,
                resultText,
                errorMessage);
    }

    private void requireStatus(JobStatus requiredStatus) {
        if (status != requiredStatus) {
            throw new IllegalStateException(
                    "Cannot transition job from " + status + "; expected " + requiredStatus);
        }
    }

    private static Instant notBefore(Instant time, Instant lowerBound) {
        Objects.requireNonNull(time, "time");
        return time.isBefore(lowerBound) ? lowerBound : time;
    }
}
