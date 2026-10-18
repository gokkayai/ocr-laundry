package dev.ig.ocrlaundry.ocr;

import java.time.Duration;
import java.util.Objects;

public record OcrResult(
        int pageCount,
        String text,
        Duration processingDuration,
        String errorMessage) {

    public OcrResult {
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount cannot be negative");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(processingDuration, "processingDuration");
        Objects.requireNonNull(errorMessage, "errorMessage");
    }

    public static OcrResult success(int pageCount, String text, Duration duration) {
        return new OcrResult(pageCount, text, duration, "");
    }

    public static OcrResult failure(int pageCount, Duration duration, String errorMessage) {
        return new OcrResult(pageCount, "", duration, errorMessage);
    }

    public boolean successful() {
        return errorMessage.isEmpty();
    }
}
