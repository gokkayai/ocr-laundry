package dev.ig.ocrlaundry.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import dev.ig.ocrlaundry.core.CancellationToken;
import dev.ig.ocrlaundry.docs.PdfInspector;

public final class TesseractOcrRunner implements OcrRunner {
    public static final int DEFAULT_DPI = 200;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final long CANCELLATION_POLL_MILLIS = 100;

    private final PdfInspector pdfInspector;
    private final String executable;
    private final String language;
    private final int dpi;
    private final Duration timeout;
    private final Path tempRoot;

    public TesseractOcrRunner() {
        this(new PdfInspector(), "tesseract", "eng", DEFAULT_DPI, DEFAULT_TIMEOUT, null);
    }

    public TesseractOcrRunner(
            PdfInspector pdfInspector,
            String executable,
            String language,
            int dpi,
            Duration timeout,
            Path tempRoot) {
        this.pdfInspector = Objects.requireNonNull(pdfInspector, "pdfInspector");
        this.executable = requireText(executable, "executable");
        this.language = requireText(language, "language");
        if (dpi <= 0) {
            throw new IllegalArgumentException("dpi must be positive");
        }
        this.dpi = dpi;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.tempRoot = tempRoot;
    }

    public OcrResult run(Path pdfPath) {
        return run(pdfPath, new CancellationToken());
    }

    @Override
    public OcrResult run(Path pdfPath, CancellationToken cancellationToken) {
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        int pageCount = 0;
        Path tempDirectory = null;

        try {
            ensureCanContinue(deadline, cancellationToken);
            pageCount = pdfInspector.pageCount(pdfPath);
            ensureCanContinue(deadline, cancellationToken);
            tempDirectory = createTempDirectory();
            StringBuilder text = new StringBuilder();

            try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
                PDFRenderer renderer = new PDFRenderer(document);

                for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                    ensureCanContinue(deadline, cancellationToken);
                    Path imagePath = tempDirectory.resolve("page-" + pageIndex + ".png");
                    Path textPath = tempDirectory.resolve("page-" + pageIndex + ".txt");
                    Path errorPath = tempDirectory.resolve("page-" + pageIndex + ".err");

                    renderPage(renderer, pageIndex, imagePath);
                    ensureCanContinue(deadline, cancellationToken);
                    runTesseract(imagePath, textPath, errorPath, deadline, cancellationToken);
                    ensureCanContinue(deadline, cancellationToken);

                    if (!text.isEmpty()) {
                        text.append(System.lineSeparator());
                    }
                    text.append(Files.readString(textPath, StandardCharsets.UTF_8).strip());
                }
            }

            return OcrResult.success(pageCount, text.toString(), elapsedSince(startedAt));
        } catch (Exception exception) {
            return OcrResult.failure(pageCount, elapsedSince(startedAt), errorMessage(exception));
        } finally {
            deleteTempDirectory(tempDirectory);
        }
    }

    private Path createTempDirectory() throws IOException {
        if (tempRoot == null) {
            return Files.createTempDirectory("ocr-laundry-");
        }
        Files.createDirectories(tempRoot);
        return Files.createTempDirectory(tempRoot, "ocr-laundry-");
    }

    private void renderPage(PDFRenderer renderer, int pageIndex, Path imagePath) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        try {
            if (!ImageIO.write(image, "png", imagePath.toFile())) {
                throw new IOException("No PNG image writer is available");
            }
        } finally {
            image.flush();
        }
    }

    private void runTesseract(
            Path imagePath,
            Path textPath,
            Path errorPath,
            long deadline,
            CancellationToken cancellationToken)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                executable,
                imagePath.toString(),
                "stdout",
                "-l",
                language)
                .redirectOutput(textPath.toFile())
                .redirectError(errorPath.toFile())
                .start();

        try {
            while (process.isAlive()) {
                if (cancellationToken.isCancellationRequested()) {
                    stopProcess(process);
                    throw new IOException("OCR was cancelled");
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    stopProcess(process);
                    throw new IOException("OCR timed out after " + timeout);
                }

                long pollNanos = TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS);
                process.waitFor(Math.min(remainingNanos, pollNanos), TimeUnit.NANOSECONDS);
            }

            if (cancellationToken.isCancellationRequested()) {
                stopProcess(process);
                throw new IOException("OCR was cancelled");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            throw exception;
        }

        if (process.exitValue() != 0) {
            String error = Files.readString(errorPath, StandardCharsets.UTF_8).strip();
            throw new IOException("Tesseract exited with code " + process.exitValue()
                    + (error.isEmpty() ? "" : ": " + error));
        }
    }

    private static void stopProcess(Process process) throws InterruptedException {
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            throw exception;
        }
    }

    private static void ensureCanContinue(long deadline, CancellationToken cancellationToken)
            throws IOException {
        if (cancellationToken.isCancellationRequested()) {
            throw new IOException("OCR was cancelled");
        }
        if (System.nanoTime() >= deadline) {
            throw new IOException("OCR timed out while rendering PDF pages");
        }
    }

    private static String errorMessage(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "OCR was interrupted";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static void deleteTempDirectory(Path tempDirectory) {
        if (tempDirectory == null) {
            return;
        }

        try (var paths = Files.walk(tempDirectory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Cleanup should not replace the OCR result with another failure.
                }
            });
        } catch (IOException ignored) {
            // The caller receives the OCR result even if best-effort cleanup fails.
        }
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
