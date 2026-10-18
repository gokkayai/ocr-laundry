package dev.ig.ocrlaundry.ocr;

import java.nio.file.Path;

import dev.ig.ocrlaundry.core.CancellationToken;

@FunctionalInterface
public interface OcrRunner {
    OcrResult run(Path pdfPath, CancellationToken cancellationToken);
}
