package dev.ig.ocrlaundry.docs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.ig.ocrlaundry.model.DocumentInfo;

public final class DocumentCatalog {
    private final Map<String, DocumentInfo> documents;

    public DocumentCatalog(Map<String, Path> documentPaths, PdfInspector pdfInspector)
            throws IOException {
        Objects.requireNonNull(documentPaths, "documentPaths");
        Objects.requireNonNull(pdfInspector, "pdfInspector");

        Map<String, DocumentInfo> inspectedDocuments = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : documentPaths.entrySet()) {
            String id = Objects.requireNonNull(entry.getKey(), "document ID");
            Path path = Objects.requireNonNull(entry.getValue(), "document path");
            if (id.isBlank()) {
                throw new IllegalArgumentException("Document ID cannot be blank");
            }

            Path normalizedPath = path.toAbsolutePath().normalize();
            int pageCount = pdfInspector.pageCount(normalizedPath);
            inspectedDocuments.put(id, new DocumentInfo(id, normalizedPath, pageCount));
        }
        documents = Collections.unmodifiableMap(inspectedDocuments);
    }

    public DocumentInfo require(String documentId) {
        DocumentInfo document = documents.get(documentId);
        if (document == null) {
            throw new IllegalArgumentException("Unknown document ID: " + documentId);
        }
        return document;
    }

    public List<DocumentInfo> documents() {
        return List.copyOf(documents.values());
    }

    public int size() {
        return documents.size();
    }
}
