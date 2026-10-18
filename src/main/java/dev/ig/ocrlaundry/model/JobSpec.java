package dev.ig.ocrlaundry.model;

import java.util.Objects;

public record JobSpec(String documentId, int priority) {
    public JobSpec {
        Objects.requireNonNull(documentId, "documentId");
        if (documentId.isBlank()) {
            throw new IllegalArgumentException("documentId cannot be blank");
        }
    }
}
