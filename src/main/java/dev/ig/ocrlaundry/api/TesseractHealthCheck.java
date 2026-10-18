package dev.ig.ocrlaundry.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class TesseractHealthCheck {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private TesseractHealthCheck() {
    }

    static boolean isAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("tesseract", "--list-langs")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && output.lines().anyMatch("eng"::equals);
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
