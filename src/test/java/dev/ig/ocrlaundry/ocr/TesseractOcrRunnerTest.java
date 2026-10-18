package dev.ig.ocrlaundry.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.ig.ocrlaundry.core.CancellationToken;
import dev.ig.ocrlaundry.docs.PdfInspector;

class TesseractOcrRunnerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void extractsTextFromImageOnlyPdfAndCleansTemporaryFiles() throws IOException {
        Path pdfPath = createImageOnlyPdf();
        Path ocrTempDirectory = tempDirectory.resolve("ocr-temp");
        TesseractOcrRunner runner = new TesseractOcrRunner(
                new PdfInspector(),
                "tesseract",
                "eng",
                200,
                Duration.ofSeconds(30),
                ocrTempDirectory);

        OcrResult result = runner.run(pdfPath);

        assertTrue(result.successful(), result.errorMessage());
        assertEquals(1, result.pageCount());
        assertTrue(result.text().contains("LAUNDRY OCR TEST"), result.text());
        assertTrue(result.processingDuration().toNanos() > 0);
        assertTemporaryDirectoryIsEmpty(ocrTempDirectory);
    }

    @Test
    void reportsTimeoutAndCleansTemporaryFiles() throws IOException {
        Path pdfPath = createImageOnlyPdf();
        Path ocrTempDirectory = tempDirectory.resolve("timeout-temp");
        TesseractOcrRunner runner = new TesseractOcrRunner(
                new PdfInspector(),
                "tesseract",
                "eng",
                200,
                Duration.ofNanos(1),
                ocrTempDirectory);

        OcrResult result = runner.run(pdfPath);

        assertTrue(!result.successful());
        assertTrue(result.errorMessage().contains("timed out"), result.errorMessage());
        assertTemporaryDirectoryIsEmpty(ocrTempDirectory);
    }

    @Test
    void reportsMissingExecutableAndCleansTemporaryFiles() throws IOException {
        Path pdfPath = createImageOnlyPdf();
        Path ocrTempDirectory = tempDirectory.resolve("missing-executable-temp");
        TesseractOcrRunner runner = new TesseractOcrRunner(
                new PdfInspector(),
                tempDirectory.resolve("missing-tesseract").toString(),
                "eng",
                200,
                Duration.ofSeconds(30),
                ocrTempDirectory);

        OcrResult result = runner.run(pdfPath);

        assertTrue(!result.successful());
        assertTrue(result.errorMessage().contains("missing-tesseract"), result.errorMessage());
        assertTemporaryDirectoryIsEmpty(ocrTempDirectory);
    }

    @Test
    void cancellationStopsStalledProcessAndCleansTemporaryFiles()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        Path pdfPath = createImageOnlyPdf();
        Path processStarted = tempDirectory.resolve("cancel-process-started");
        Path executable = createStallingExecutable(processStarted);
        Path ocrTempDirectory = tempDirectory.resolve("cancel-temp");
        TesseractOcrRunner runner = new TesseractOcrRunner(
                new PdfInspector(),
                executable.toString(),
                "eng",
                200,
                Duration.ofSeconds(30),
                ocrTempDirectory);
        CancellationToken cancellationToken = new CancellationToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<OcrResult> future = executor.submit(
                    () -> runner.run(pdfPath, cancellationToken));
            assertTrue(awaitFile(processStarted, Duration.ofSeconds(5)));

            assertTrue(cancellationToken.cancel());
            OcrResult result = future.get(5, TimeUnit.SECONDS);

            assertFalse(result.successful());
            assertTrue(result.errorMessage().contains("cancelled"), result.errorMessage());
            assertTemporaryDirectoryIsEmpty(ocrTempDirectory);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timeoutStopsStalledProcessAndCleansTemporaryFiles() throws IOException {
        Path pdfPath = createImageOnlyPdf();
        Path processStarted = tempDirectory.resolve("timeout-process-started");
        Path executable = createStallingExecutable(processStarted);
        Path ocrTempDirectory = tempDirectory.resolve("stalled-timeout-temp");
        TesseractOcrRunner runner = new TesseractOcrRunner(
                new PdfInspector(),
                executable.toString(),
                "eng",
                200,
                Duration.ofSeconds(2),
                ocrTempDirectory);

        OcrResult result = runner.run(pdfPath);

        assertTrue(Files.exists(processStarted), "Stalling process was not started");
        assertFalse(result.successful());
        assertTrue(result.errorMessage().contains("timed out"), result.errorMessage());
        assertTemporaryDirectoryIsEmpty(ocrTempDirectory);
    }

    private Path createImageOnlyPdf() throws IOException {
        BufferedImage image = new BufferedImage(1_600, 400, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 110));
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString("LAUNDRY OCR TEST", 140, 245);
        } finally {
            graphics.dispose();
        }

        Path pdfPath = tempDirectory.resolve("scanned-test.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDImageXObject pdfImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 36, 500, 540, 135);
            }
            document.save(pdfPath.toFile());
        } finally {
            image.flush();
        }
        return pdfPath;
    }

    private Path createStallingExecutable(Path processStarted) throws IOException {
        Path executable = tempDirectory.resolve("stall-tesseract-" + processStarted.getFileName());
        String script = "#!/bin/sh\n"
                + "touch \"" + processStarted + "\"\n"
                + "exec sleep 30\n";
        Files.writeString(executable, script);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private static boolean awaitFile(Path path, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                return true;
            }
            Thread.sleep(10);
        }
        return Files.exists(path);
    }

    private static void assertTemporaryDirectoryIsEmpty(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count(), "OCR temporary files were not deleted");
        }
    }
}
