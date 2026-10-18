package dev.ig.ocrlaundry.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.ig.ocrlaundry.core.SchedulerEngine;
import dev.ig.ocrlaundry.docs.DocumentCatalog;
import dev.ig.ocrlaundry.docs.PdfInspector;
import dev.ig.ocrlaundry.model.JobSpec;
import dev.ig.ocrlaundry.model.SchedulingPolicy;
import dev.ig.ocrlaundry.ocr.OcrResult;
import dev.ig.ocrlaundry.ocr.OcrRunner;

class OcrLaundryServerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void reportsHealthAndAllowlistedDocumentsWithoutPaths() throws Exception {
        try (TestApplication application = start(false, true)) {
            HttpResponse<String> health = application.get("/health");
            HttpResponse<String> documents = application.get("/api/documents");

            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"ok\""));
            assertEquals(200, documents.statusCode());
            assertTrue(documents.body().contains("\"id\":\"small\""));
            assertTrue(documents.body().contains("\"pageCount\":1"));
            assertTrue(!documents.body().contains(tempDirectory.toString()));
        }
    }

    @Test
    void submitsInspectsAndMeasuresACompletedJob() throws Exception {
        try (TestApplication application = start(false, true)) {
            HttpResponse<String> submitted = application.post(
                    "/api/jobs",
                    "{\"documentId\":\"small\",\"priority\":5}",
                    "application/json");

            assertEquals(202, submitted.statusCode());
            String location = submitted.headers().firstValue("Location").orElseThrow();
            assertTrue(application.scheduler().awaitIdle(Duration.ofSeconds(5)));

            HttpResponse<String> job = application.get(location);
            HttpResponse<String> jobs = application.get("/api/jobs");
            HttpResponse<String> metrics = application.get("/api/metrics");

            assertEquals(200, job.statusCode());
            assertTrue(job.body().contains("\"status\":\"COMPLETED\""));
            assertTrue(job.body().contains("recognized \\\"text\\\"\\nnext"));
            assertTrue(jobs.body().contains(location.substring("/api/jobs/".length())));
            assertTrue(metrics.body().contains("\"completedJobs\":1"));
            assertTrue(metrics.body().contains("\"meanCompletionLatencyMs\":"));
        }
    }

    @Test
    void cancelsAQueuedJobAndRejectsAnotherCancellation() throws Exception {
        try (TestApplication application = start(true, true)) {
            HttpResponse<String> submitted = application.post(
                    "/api/jobs",
                    "{\"documentId\":\"small\"}",
                    "application/json; charset=utf-8");
            String location = submitted.headers().firstValue("Location").orElseThrow();

            HttpResponse<String> cancelled = application.delete(location);
            HttpResponse<String> repeated = application.delete(location);

            assertEquals(202, cancelled.statusCode());
            assertTrue(cancelled.body().contains("\"status\":\"CANCELLED\""));
            assertEquals(409, repeated.statusCode());
            assertTrue(repeated.body().contains("cannot be cancelled"));
        }
    }

    @Test
    void returnsMeaningfulClientAndCapacityErrors() throws Exception {
        try (TestApplication application = start(true, false)) {
            HttpResponse<String> health = application.get("/health");
            HttpResponse<String> wrongType = application.post(
                    "/api/jobs",
                    "{}",
                    "text/plain");
            HttpResponse<String> unknown = application.post(
                    "/api/jobs",
                    "{\"documentId\":\"missing\"}",
                    "application/json");
            for (int index = 0; index < SchedulerEngine.DEFAULT_PENDING_CAPACITY; index++) {
                application.scheduler().submit(new JobSpec("small", 0));
            }
            HttpResponse<String> full = application.post(
                    "/api/jobs",
                    "{\"documentId\":\"small\"}",
                    "application/json");

            assertEquals(503, health.statusCode());
            assertEquals(415, wrongType.statusCode());
            assertEquals(400, unknown.statusCode());
            assertEquals(429, full.statusCode());
            assertTrue(full.body().contains("queue is full"));
        }
    }

    private TestApplication start(boolean paused, boolean healthy) throws IOException {
        Path pdf = tempDirectory.resolve("small.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        DocumentCatalog catalog = new DocumentCatalog(Map.of("small", pdf), new PdfInspector());
        OcrRunner runner = (path, cancellationToken) -> OcrResult.success(
                1,
                "recognized \"text\"\nnext",
                Duration.ZERO);
        SchedulerEngine scheduler = paused
                ? SchedulerEngine.paused(catalog, runner, SchedulingPolicy.FIFO, 1)
                : new SchedulerEngine(catalog, runner, SchedulingPolicy.FIFO, 1);
        OcrLaundryServer server = new OcrLaundryServer(
                new InetSocketAddress("127.0.0.1", 0),
                catalog,
                scheduler,
                () -> healthy);
        server.start();
        return new TestApplication(server, scheduler);
    }

    private record TestApplication(
            OcrLaundryServer server,
            SchedulerEngine scheduler) implements AutoCloseable {
        private static final HttpClient CLIENT = HttpClient.newHttpClient();

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).GET().build());
        }

        HttpResponse<String> post(String path, String body, String contentType)
                throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build());
        }

        HttpResponse<String> delete(String path) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).DELETE().build());
        }

        private HttpResponse<String> send(HttpRequest request)
                throws IOException, InterruptedException {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.port() + path);
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
