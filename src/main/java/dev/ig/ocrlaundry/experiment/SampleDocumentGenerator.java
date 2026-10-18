package dev.ig.ocrlaundry.experiment;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class SampleDocumentGenerator {
    private static final int IMAGE_WIDTH = 1_275;
    private static final int IMAGE_HEIGHT = 1_650;
    private static final long RANDOM_SEED = 20_260_918L;

    private SampleDocumentGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDirectory = args.length == 0 ? Path.of("samples") : Path.of(args[0]);
        Map<String, Path> samples = generate(outputDirectory);
        for (Path sample : samples.values()) {
            System.out.println("Generated " + sample);
        }
    }

    public static Map<String, Path> generate(Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        Map<String, Path> samples = new LinkedHashMap<>();
        samples.put("long", createPdf(outputDirectory.resolve("scanned-long.pdf"), 4, 2));
        samples.put("medium", createPdf(outputDirectory.resolve("scanned-medium.pdf"), 2, 1));
        samples.put("small", createPdf(outputDirectory.resolve("scanned-small.pdf"), 1, 0));
        return samples;
    }

    private static Path createPdf(Path outputPath, int pageCount, int qualityLevel)
            throws IOException {
        Path temporaryPath = Files.createTempFile(outputPath.getParent(), "ocr-laundry-sample-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage scan = createScan(pageIndex, qualityLevel);
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(document, scan);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(image, 0, 0, page.getMediaBox().getWidth(),
                            page.getMediaBox().getHeight());
                }
                scan.flush();
            }
            document.save(temporaryPath.toFile());
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryPath);
            throw exception;
        }
        Files.move(temporaryPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
        return outputPath.toAbsolutePath().normalize();
    }

    private static BufferedImage createScan(int pageIndex, int qualityLevel) {
        BufferedImage image = new BufferedImage(
                IMAGE_WIDTH,
                IMAGE_HEIGHT,
                BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            int background = 255 - qualityLevel * 8;
            int foreground = 20 + qualityLevel * 24;
            graphics.setColor(new Color(background, background, background));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(foreground, foreground, foreground));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 45));
            graphics.drawString("OCR LAUNDRY BENCHMARK", 95, 120);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 29));

            String[] lines = {
                    "Synthetic scanned document page " + (pageIndex + 1),
                    "Scheduling experiments compare queue completion times.",
                    "Each file contains raster images instead of searchable text.",
                    "The quick brown fox jumps over the lazy dog.",
                    "Invoice reference 4815 1623 4200 and batch 2026.",
                    "Workers render each page before optical character recognition.",
                    "Short jobs may finish sooner when estimates are accurate.",
                    "Measurements include waiting runtime and total latency.",
                    "This content is generated locally for repeatable testing."
            };
            int y = 215;
            for (int block = 0; block < 4; block++) {
                for (String line : lines) {
                    graphics.drawString(line, 95, y);
                    y += 34;
                }
                y += 28;
            }

            addNoise(image, qualityLevel, pageIndex);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void addNoise(BufferedImage image, int qualityLevel, int pageIndex) {
        if (qualityLevel == 0) {
            return;
        }
        Random random = new Random(RANDOM_SEED + qualityLevel * 100L + pageIndex);
        int speckleCount = qualityLevel * 7_000;
        for (int index = 0; index < speckleCount; index++) {
            int x = random.nextInt(IMAGE_WIDTH);
            int y = random.nextInt(IMAGE_HEIGHT);
            int gray = 150 + random.nextInt(90);
            image.getRaster().setSample(x, y, 0, gray);
        }
    }
}
