package dev.ig.ocrlaundry.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

public final class PdfInspector {
    public static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;
    public static final int DEFAULT_MAX_PAGES = 100;

    private final long maxBytes;
    private final int maxPages;

    public PdfInspector() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_PAGES);
    }

    public PdfInspector(long maxBytes, int maxPages) {
        if (maxBytes <= 0 || maxPages <= 0) {
            throw new IllegalArgumentException("PDF limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxPages = maxPages;
    }

    public int pageCount(Path pdfPath) throws IOException {
        Objects.requireNonNull(pdfPath, "pdfPath");

        if (!Files.isRegularFile(pdfPath)) {
            throw new IOException("PDF does not exist or is not a regular file: " + pdfPath);
        }

        long fileSize = Files.size(pdfPath);
        if (fileSize > maxBytes) {
            throw new IOException("PDF is too large: " + fileSize + " bytes; limit is " + maxBytes);
        }

        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new IOException("PDF has no pages");
            }
            if (pageCount > maxPages) {
                throw new IOException("PDF has too many pages: " + pageCount + "; limit is " + maxPages);
            }
            return pageCount;
        } catch (InvalidPasswordException exception) {
            throw new IOException("Encrypted PDFs are not supported", exception);
        } catch (IOException exception) {
            if (exception.getMessage() != null
                    && (exception.getMessage().startsWith("PDF has no pages")
                            || exception.getMessage().startsWith("PDF has too many pages"))) {
                throw exception;
            }
            throw new IOException("Could not read PDF: " + pdfPath, exception);
        }
    }
}
