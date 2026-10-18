package dev.ig.ocrlaundry.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.ig.ocrlaundry.core.QueueFullException;
import dev.ig.ocrlaundry.core.SchedulerEngine;
import dev.ig.ocrlaundry.docs.DocumentCatalog;
import dev.ig.ocrlaundry.model.DocumentInfo;
import dev.ig.ocrlaundry.model.JobSnapshot;
import dev.ig.ocrlaundry.model.JobSpec;
import dev.ig.ocrlaundry.model.JobStatus;

public final class OcrLaundryServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int TEXT_PREVIEW_LENGTH = 300;

    private final HttpServer server;
    private final DocumentCatalog catalog;
    private final SchedulerEngine scheduler;
    private final BooleanSupplier ocrHealthCheck;
    private final ExecutorService requestExecutor;

    public OcrLaundryServer(
            InetSocketAddress address,
            DocumentCatalog catalog,
            SchedulerEngine scheduler,
            BooleanSupplier ocrHealthCheck) throws IOException {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ocrHealthCheck = Objects.requireNonNull(ocrHealthCheck, "ocrHealthCheck");
        server = HttpServer.create(address, 0);
        requestExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(requestExecutor);
        server.createContext("/health", this::handleSafely);
        server.createContext("/api/documents", this::handleSafely);
        server.createContext("/api/jobs", this::handleSafely);
        server.createContext("/api/metrics", this::handleSafely);
        server.createContext("/", this::handleSafely);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        requestExecutor.shutdownNow();
        scheduler.close();
    }

    private void handleSafely(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health")) {
                handleHealth(exchange);
            } else if (path.equals("/api/documents")) {
                handleDocuments(exchange);
            } else if (path.equals("/api/metrics")) {
                handleMetrics(exchange);
            } else if (path.equals("/api/jobs") || path.startsWith("/api/jobs/")) {
                handleJobs(exchange, path);
            } else {
                handleDashboardAsset(exchange, path);
            }
        } catch (RuntimeException exception) {
            sendError(exchange, 500, "Unexpected server error");
        } finally {
            exchange.close();
        }
    }

    private void handleDashboardAsset(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            methodNotAllowed(exchange, "GET, HEAD");
            return;
        }
        Optional<DashboardAssets.Asset> asset = DashboardAssets.find(path);
        if (asset.isEmpty()) {
            sendError(exchange, 404, "Endpoint not found");
            return;
        }
        DashboardAssets.Asset found = asset.orElseThrow();
        sendBytes(exchange, 200, found.contentType(), found.read(), false);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        boolean available = ocrHealthCheck.getAsBoolean();
        String json = "{\"status\":" + FlatJsonObject.quote(available ? "ok" : "degraded")
                + ",\"ocr\":{\"executable\":\"tesseract\",\"available\":"
                + available + ",\"language\":\"eng\"}}";
        sendJson(exchange, available ? 200 : 503, json);
    }

    private void handleDocuments(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        StringBuilder json = new StringBuilder("{\"documents\":[");
        List<DocumentInfo> documents = catalog.documents();
        for (int index = 0; index < documents.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            DocumentInfo document = documents.get(index);
            json.append("{\"id\":").append(FlatJsonObject.quote(document.id()))
                    .append(",\"pageCount\":").append(document.pageCount()).append('}');
        }
        sendJson(exchange, 200, json.append("]}").toString());
    }

    private void handleJobs(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/jobs")) {
            if (exchange.getRequestMethod().equals("GET")) {
                listJobs(exchange);
            } else if (exchange.getRequestMethod().equals("POST")) {
                submitJob(exchange);
            } else {
                methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }

        Optional<UUID> jobId = parseJobId(path.substring("/api/jobs/".length()));
        if (jobId.isEmpty()) {
            sendError(exchange, 400, "Job ID must be a UUID");
            return;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            getJob(exchange, jobId.orElseThrow());
        } else if (exchange.getRequestMethod().equals("DELETE")) {
            cancelJob(exchange, jobId.orElseThrow());
        } else {
            methodNotAllowed(exchange, "GET, DELETE");
        }
    }

    private void listJobs(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("{\"jobs\":[");
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.equals("view=dashboard")) {
            sendError(exchange, 400, "Supported query: view=dashboard");
            return;
        }
        List<JobSnapshot> jobs = query == null ? scheduler.jobs() : scheduler.dashboardJobs();
        for (int index = 0; index < jobs.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(jobJson(jobs.get(index)));
        }
        String body = json.append("]}").toString();
        String etag;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            etag = "\"" + java.util.HexFormat.of().formatHex(digest) + "\"";
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, private");
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        sendJson(exchange, 200, body);
    }

    private void submitJob(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT)
                .startsWith("application/json")) {
            sendError(exchange, 415, "Content-Type must be application/json");
            return;
        }

        Map<String, Object> request;
        try {
            request = FlatJsonObject.parse(readRequestBody(exchange));
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
            return;
        }
        Object documentValue = request.get("documentId");
        if (!(documentValue instanceof String documentId) || documentId.isBlank()) {
            sendError(exchange, 400, "documentId must be a non-empty string");
            return;
        }
        Object priorityValue = request.getOrDefault("priority", 0);
        if (!(priorityValue instanceof Integer priority)) {
            sendError(exchange, 400, "priority must be an integer");
            return;
        }

        try {
            JobSnapshot job = scheduler.submit(new JobSpec(documentId, priority));
            exchange.getResponseHeaders().set("Location", "/api/jobs/" + job.id());
            sendJson(exchange, 202, jobJson(job));
        } catch (QueueFullException exception) {
            sendError(exchange, 429, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (IllegalStateException exception) {
            sendError(exchange, 503, exception.getMessage());
        }
    }

    private void getJob(HttpExchange exchange, UUID jobId) throws IOException {
        Optional<JobSnapshot> job = scheduler.find(jobId);
        if (job.isEmpty()) {
            sendError(exchange, 404, "Job not found");
            return;
        }
        sendJson(exchange, 200, jobJson(job.orElseThrow()));
    }

    private void cancelJob(HttpExchange exchange, UUID jobId) throws IOException {
        Optional<JobSnapshot> beforeCancellation = scheduler.find(jobId);
        if (beforeCancellation.isEmpty()) {
            sendError(exchange, 404, "Job not found");
            return;
        }
        if (!scheduler.cancel(jobId)) {
            sendError(
                    exchange,
                    409,
                    "Job cannot be cancelled from " + beforeCancellation.orElseThrow().status());
            return;
        }
        sendJson(exchange, 202, jobJson(scheduler.find(jobId).orElseThrow()));
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        List<JobSnapshot> jobs = scheduler.jobs();
        List<Double> completionTimes = jobs.stream()
                .filter(job -> job.status() == JobStatus.COMPLETED)
                .map(job -> durationMillis(job.submittedAt(), job.finishedAt()))
                .sorted()
                .toList();
        long failures = jobs.stream().filter(job -> job.status() == JobStatus.FAILED).count();
        long cancellations = jobs.stream().filter(job -> job.status() == JobStatus.CANCELLED).count();
        String json = "{\"policy\":" + FlatJsonObject.quote(scheduler.schedulingPolicy().name())
                + ",\"pendingJobs\":" + scheduler.pendingCount()
                + ",\"activeWorkers\":" + scheduler.runningCount()
                + ",\"workerCount\":" + scheduler.workerCount()
                + ",\"totalJobs\":" + jobs.size()
                + ",\"completedJobs\":" + completionTimes.size()
                + ",\"failedJobs\":" + failures
                + ",\"cancelledJobs\":" + cancellations
                + ",\"meanCompletionLatencyMs\":" + numberOrNull(mean(completionTimes))
                + ",\"medianCompletionLatencyMs\":" + numberOrNull(percentile(completionTimes, 0.50))
                + ",\"p95CompletionLatencyMs\":" + numberOrNull(percentile(completionTimes, 0.95))
                + '}';
        sendJson(exchange, 200, json);
    }

    private static String jobJson(JobSnapshot job) {
        return "{\"id\":" + FlatJsonObject.quote(job.id().toString())
                + ",\"documentId\":" + FlatJsonObject.quote(job.documentId())
                + ",\"priority\":" + job.priority()
                + ",\"status\":" + FlatJsonObject.quote(job.status().name())
                + ",\"pageCount\":" + job.pageCount()
                + ",\"estimatedCost\":" + job.estimatedCost()
                + ",\"submittedAt\":" + instantOrNull(job.submittedAt())
                + ",\"startedAt\":" + instantOrNull(job.startedAt())
                + ",\"finishedAt\":" + instantOrNull(job.finishedAt())
                + ",\"queueWaitMs\":" + durationOrNull(job.submittedAt(), job.startedAt())
                + ",\"runTimeMs\":" + durationOrNull(job.startedAt(), job.finishedAt())
                + ",\"completionLatencyMs\":"
                + durationOrNull(job.submittedAt(), job.finishedAt())
                + ",\"textPreview\":" + FlatJsonObject.quote(preview(job.resultText()))
                + ",\"error\":" + FlatJsonObject.quote(job.errorMessage()) + '}';
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException(
                        "Request body exceeds " + MAX_REQUEST_BYTES + " bytes");
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static Optional<UUID> parseJobId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String preview(String text) {
        if (text.length() <= TEXT_PREVIEW_LENGTH) {
            return text;
        }
        return text.substring(0, TEXT_PREVIEW_LENGTH) + "...";
    }

    private static double durationMillis(Instant start, Instant end) {
        return Duration.between(start, end).toNanos() / 1_000_000.0;
    }

    private static String durationOrNull(Instant start, Instant end) {
        return start == null || end == null ? "null" : format(durationMillis(start, end));
    }

    private static String instantOrNull(Instant instant) {
        return instant == null ? "null" : FlatJsonObject.quote(instant.toString());
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return Double.NaN;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(index, 0));
    }

    private static String numberOrNull(double value) {
        return Double.isNaN(value) ? "null" : format(value);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equals(method)) {
            return true;
        }
        methodNotAllowed(exchange, method);
        return false;
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendError(exchange, 405, "Method not allowed");
    }

    private static void sendError(HttpExchange exchange, int status, String message)
            throws IOException {
        sendJson(exchange, status, "{\"error\":" + FlatJsonObject.quote(message) + '}');
    }

    private static void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        sendBytes(exchange, status, "application/json; charset=utf-8", body, true);
    }

    private static void sendBytes(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body,
            boolean noStore) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                        + "connect-src 'self'; base-uri 'none'; form-action 'self'");
        exchange.getResponseHeaders().set(
                "Cache-Control",
                noStore ? "no-store" : "public, max-age=300");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
