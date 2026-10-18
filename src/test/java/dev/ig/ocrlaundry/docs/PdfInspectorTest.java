package dev.ig.ocrlaundry.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfInspectorTest {
    @TempDir
    Path tempDirectory;

    @Test
    void readsPageCount() throws IOException {
        Path pdfPath = createPdf("two-pages.pdf", 2);

        assertEquals(2, new PdfInspector().pageCount(pdfPath));
    }

    @Test
    void rejectsInvalidPdf() throws IOException {
        Path pdfPath = tempDirectory.resolve("invalid.pdf");
        Files.writeString(pdfPath, "not a PDF");

        IOException exception = assertThrows(
                IOException.class,
                () -> new PdfInspector().pageCount(pdfPath));

        assertTrue(exception.getMessage().startsWith("Could not read PDF"));
    }

    @Test
    void rejectsFilesOverSizeLimit() throws IOException {
        Path pdfPath = tempDirectory.resolve("large.pdf");
        Files.writeString(pdfPath, "too large");

        IOException exception = assertThrows(
                IOException.class,
                () -> new PdfInspector(4, 10).pageCount(pdfPath));

        assertTrue(exception.getMessage().startsWith("PDF is too large"));
    }

    @Test
    void rejectsDocumentsOverPageLimit() throws IOException {
        Path pdfPath = createPdf("too-many-pages.pdf", 2);

        IOException exception = assertThrows(
                IOException.class,
                () -> new PdfInspector(1_000_000, 1).pageCount(pdfPath));

        assertTrue(exception.getMessage().startsWith("PDF has too many pages"));
    }

    @Test
    void rejectsEncryptedPdf() throws IOException {
        Path pdfPath = tempDirectory.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-password",
                    "user-password",
                    new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(pdfPath.toFile());
        }

        IOException exception = assertThrows(
                IOException.class,
                () -> new PdfInspector().pageCount(pdfPath));

        assertEquals("Encrypted PDFs are not supported", exception.getMessage());
    }

    private Path createPdf(String filename, int pageCount) throws IOException {
        Path pdfPath = tempDirectory.resolve(filename);
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pageCount; page++) {
                document.addPage(new PDPage());
            }
            document.save(pdfPath.toFile());
        }
        return pdfPath;
    }
}
