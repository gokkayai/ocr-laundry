package dev.ig.ocrlaundry.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

final class DashboardAssets {
    private static final Map<String, Asset> ASSETS = Map.of(
            "/", new Asset("dashboard/index.html", "text/html; charset=utf-8"),
            "/app.css", new Asset("dashboard/app.css", "text/css; charset=utf-8"),
            "/app.js", new Asset("dashboard/app.js", "text/javascript; charset=utf-8"),
            "/benchmark.js", new Asset("dashboard/benchmark.js", "text/javascript; charset=utf-8"),
            "/benchmark", new Asset("dashboard/benchmark.html", "text/html; charset=utf-8"),
            "/benchmark/plot.svg", new Asset(
                    "published-docs/mean-completion-latency.svg",
                    "image/svg+xml"),
            "/benchmark/raw.csv", new Asset("published-docs/raw.csv", "text/csv; charset=utf-8"),
            "/benchmark/summary.csv", new Asset(
                    "published-docs/summary.csv",
                    "text/csv; charset=utf-8"));

    private DashboardAssets() {
    }

    static Optional<Asset> find(String requestPath) {
        return Optional.ofNullable(ASSETS.get(requestPath));
    }

    record Asset(String resourcePath, String contentType) {
        byte[] read() throws IOException {
            ClassLoader classLoader = DashboardAssets.class.getClassLoader();
            try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IOException("Missing classpath resource: " + resourcePath);
                }
                return input.readAllBytes();
            }
        }
    }
}
