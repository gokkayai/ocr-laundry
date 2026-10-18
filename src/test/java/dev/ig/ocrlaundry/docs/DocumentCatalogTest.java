package dev.ig.ocrlaundry.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentCatalogTest {
    @TempDir
    Path tempDirectory;

    @Test
    void inspectsAllowlistedDocumentsAndRejectsUnknownIds() throws IOException {
        Path documentPath = createPdf("known.pdf", 2);
        DocumentCatalog catalog = new DocumentCatalog(
                Map.of("known", documentPath),
                new PdfInspector());

        assertEquals(1, catalog.size());
        assertEquals(2, catalog.require("known").pageCount());
        assertEquals(documentPath.toAbsolutePath(), catalog.require("known").path());
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown"));
    }

    private Path createPdf(String filename, int pageCount) throws IOException {
        Path path = tempDirectory.resolve(filename);
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pageCount; page++) {
                document.addPage(new PDPage());
            }
            document.save(path.toFile());
        }
        return path;
    }
}
