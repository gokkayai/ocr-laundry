package dev.ig.ocrlaundry.model;

import java.nio.file.Path;
import java.util.Objects;

public record DocumentInfo(String id, Path path, int pageCount) {
    public DocumentInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(path, "path");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (pageCount <= 0) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
        path = path.toAbsolutePath().normalize();
    }
}
