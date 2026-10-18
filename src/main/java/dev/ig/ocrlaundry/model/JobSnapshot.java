package dev.ig.ocrlaundry.model;

import java.time.Instant;
import java.util.UUID;

public record JobSnapshot(
        UUID id,
        String documentId,
        int priority,
        long submittedSequence,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        JobStatus status,
        int pageCount,
        int estimatedCost,
        String resultText,
        String errorMessage) {
}
