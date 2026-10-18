package dev.ig.ocrlaundry.ocr;

import java.nio.file.Path;

@FunctionalInterface
public interface OcrRunner {
    OcrResult run(Path pdfPath);
}
