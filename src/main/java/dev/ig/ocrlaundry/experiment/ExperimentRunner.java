package dev.ig.ocrlaundry.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import dev.ig.ocrlaundry.core.SchedulerEngine;
import dev.ig.ocrlaundry.docs.DocumentCatalog;
import dev.ig.ocrlaundry.docs.PdfInspector;
import dev.ig.ocrlaundry.model.JobSnapshot;
import dev.ig.ocrlaundry.model.JobSpec;
import dev.ig.ocrlaundry.model.JobStatus;
import dev.ig.ocrlaundry.model.SchedulingPolicy;
import dev.ig.ocrlaundry.ocr.TesseractOcrRunner;

public final class ExperimentRunner {
    private static final int DEFAULT_REPETITIONS = 3;
    private static final long ORDER_SEED = 20_261_018L;
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private static final List<String> SUBMISSION_ORDER = List.of("long", "medium", "small");

    private ExperimentRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path sampleDirectory = args.length >= 1 ? Path.of(args[0]) : Path.of("samples");
        Path resultDirectory = args.length >= 2
                ? Path.of(args[1])
                : Path.of("docs", "experiment-results");
        int repetitions = args.length >= 3
                ? positiveInteger(args[2], "repetitions")
                : DEFAULT_REPETITIONS;

        Map<String, Path> samples = samplePaths(sampleDirectory);
        DocumentCatalog catalog = new DocumentCatalog(samples, new PdfInspector());
        List<Measurement> measurements = runAll(catalog, repetitions);
        List<Summary> summaries = summarize(measurements);

        Files.createDirectories(resultDirectory);
        writeRawCsv(resultDirectory.resolve("raw.csv"), measurements);
        writeSummaryCsv(resultDirectory.resolve("summary.csv"), summaries);
        writePlot(resultDirectory.resolve("mean-completion-latency.svg"), summaries);

        printSummaries(summaries);
        System.out.println("Results written to " + resultDirectory.toAbsolutePath().normalize());
    }

    static List<Measurement> runAll(DocumentCatalog catalog, int repetitions)
            throws InterruptedException {
        List<Condition> conditions = List.of(
                new Condition(SchedulingPolicy.FIFO, 1),
                new Condition(SchedulingPolicy.ESTIMATED_SJF, 1),
                new Condition(SchedulingPolicy.FIFO, 2),
                new Condition(SchedulingPolicy.ESTIMATED_SJF, 2));
        List<Measurement> measurements = new ArrayList<>();
        Random orderRandom = new Random(ORDER_SEED);
        int runOrder = 1;

        for (int repetition = 1; repetition <= repetitions; repetition++) {
            List<Condition> randomized = new ArrayList<>(conditions);
            Collections.shuffle(randomized, orderRandom);
            for (Condition condition : randomized) {
                System.out.printf(
                        Locale.ROOT,
                        "Run %d/%d: repetition %d, %s, %d worker(s)%n",
                        runOrder,
                        repetitions * conditions.size(),
                        repetition,
                        condition.policy(),
                        condition.workers());
                measurements.addAll(runCondition(catalog, condition, repetition, runOrder));
                runOrder++;
            }
        }
        return measurements;
    }

    private static List<Measurement> runCondition(
            DocumentCatalog catalog,
            Condition condition,
            int repetition,
            int runOrder) throws InterruptedException {
        SchedulerEngine engine = SchedulerEngine.paused(
                catalog,
                new TesseractOcrRunner(),
                condition.policy(),
                condition.workers());
        long wallStarted = System.nanoTime();
        try {
            for (String documentId : SUBMISSION_ORDER) {
                engine.submit(new JobSpec(documentId, 0));
            }
            engine.start();
            if (!engine.awaitIdle(RUN_TIMEOUT)) {
                throw new IllegalStateException("Experiment run timed out after " + RUN_TIMEOUT);
            }
            double wallMillis = nanosToMillis(System.nanoTime() - wallStarted);
            return engine.jobs().stream()
                    .sorted(Comparator.comparingLong(JobSnapshot::submittedSequence))
                    .map(job -> Measurement.from(
                            runOrder,
                            repetition,
                            condition,
                            wallMillis,
                            job))
                    .toList();
        } finally {
            engine.close();
        }
    }

    static List<Summary> summarize(List<Measurement> measurements) {
        Map<Condition, List<Measurement>> byCondition = new LinkedHashMap<>();
        for (Measurement measurement : measurements) {
            byCondition.computeIfAbsent(measurement.condition(), ignored -> new ArrayList<>())
                    .add(measurement);
        }

        List<Summary> summaries = new ArrayList<>();
        for (Map.Entry<Condition, List<Measurement>> entry : byCondition.entrySet()) {
            List<Measurement> rows = entry.getValue();
            List<Measurement> successes = rows.stream()
                    .filter(row -> row.status() == JobStatus.COMPLETED)
                    .toList();
            List<Double> completionTimes = successes.stream()
                    .map(Measurement::completionLatencyMillis)
                    .sorted()
                    .toList();
            double totalWallMillis = wallTimeByRun(rows);
            int successfulPages = successes.stream().mapToInt(Measurement::pageCount).sum();
            summaries.add(new Summary(
                    entry.getKey(),
                    successes.size(),
                    rows.size() - successes.size(),
                    mean(completionTimes),
                    percentile(completionTimes, 0.50),
                    percentile(completionTimes, 0.95),
                    rows.stream().mapToDouble(Measurement::queueWaitMillis).max().orElse(0),
                    meanForDocument(successes, "small"),
                    meanForDocument(successes, "medium"),
                    meanForDocument(successes, "long"),
                    perMinute(successes.size(), totalWallMillis),
                    perMinute(successfulPages, totalWallMillis),
                    totalWallMillis));
        }
        summaries.sort(Comparator
                .comparingInt((Summary summary) -> summary.condition().workers())
                .thenComparing(summary -> summary.condition().policy()));
        return summaries;
    }

    static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return Double.NaN;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, index));
    }

    private static double wallTimeByRun(List<Measurement> rows) {
        Map<Integer, Double> wallTimes = new LinkedHashMap<>();
        for (Measurement row : rows) {
            wallTimes.put(row.runOrder(), row.wallMillis());
        }
        return wallTimes.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double meanForDocument(List<Measurement> rows, String documentId) {
        return rows.stream()
                .filter(row -> row.documentId().equals(documentId))
                .mapToDouble(Measurement::completionLatencyMillis)
                .average()
                .orElse(Double.NaN);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double perMinute(int completedUnits, double wallMillis) {
        return completedUnits == 0 ? 0 : completedUnits * 60_000.0 / wallMillis;
    }

    private static void writeRawCsv(Path path, List<Measurement> measurements) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("run_order,repetition,policy,workers,arrival_pattern,document_id,")
                .append("page_count,status,submitted_at,started_at,finished_at,")
                .append("queue_wait_ms,run_time_ms,completion_latency_ms,run_wall_ms,error\n");
        for (Measurement row : measurements) {
            csv.append(row.runOrder()).append(',')
                    .append(row.repetition()).append(',')
                    .append(row.condition().policy()).append(',')
                    .append(row.condition().workers()).append(',')
                    .append("full_backlog,")
                    .append(row.documentId()).append(',')
                    .append(row.pageCount()).append(',')
                    .append(row.status()).append(',')
                    .append(row.submittedAt()).append(',')
                    .append(row.startedAt()).append(',')
                    .append(row.finishedAt()).append(',')
                    .append(format(row.queueWaitMillis())).append(',')
                    .append(format(row.runTimeMillis())).append(',')
                    .append(format(row.completionLatencyMillis())).append(',')
                    .append(format(row.wallMillis())).append(',')
                    .append(csvValue(row.errorMessage())).append('\n');
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    private static void writeSummaryCsv(Path path, List<Summary> summaries) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("policy,workers,successful_jobs,failed_jobs,mean_completion_ms,")
                .append("median_completion_ms,p95_completion_ms,max_queue_wait_ms,")
                .append("small_mean_ms,medium_mean_ms,long_mean_ms,documents_per_minute,")
                .append("pages_per_minute,total_wall_ms\n");
        for (Summary summary : summaries) {
            csv.append(summary.condition().policy()).append(',')
                    .append(summary.condition().workers()).append(',')
                    .append(summary.successfulJobs()).append(',')
                    .append(summary.failedJobs()).append(',')
                    .append(format(summary.meanCompletionMillis())).append(',')
                    .append(format(summary.medianCompletionMillis())).append(',')
                    .append(format(summary.p95CompletionMillis())).append(',')
                    .append(format(summary.maxQueueWaitMillis())).append(',')
                    .append(format(summary.smallMeanMillis())).append(',')
                    .append(format(summary.mediumMeanMillis())).append(',')
                    .append(format(summary.longMeanMillis())).append(',')
                    .append(format(summary.documentsPerMinute())).append(',')
                    .append(format(summary.pagesPerMinute())).append(',')
                    .append(format(summary.totalWallMillis())).append('\n');
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    private static void writePlot(Path path, List<Summary> summaries) throws IOException {
        int width = 760;
        int height = 430;
        int baseline = 350;
        double maximum = summaries.stream()
                .mapToDouble(Summary::meanCompletionMillis)
                .max()
                .orElse(1);
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(width).append("\" height=\"").append(height).append("\" ")
                .append("viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">\n")
                .append("  <rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n")
                .append("  <text x=\"380\" y=\"30\" text-anchor=\"middle\" ")
                .append("font-family=\"sans-serif\" font-size=\"20\">Mean completion latency</text>\n")
                .append("  <line x1=\"70\" y1=\"").append(baseline)
                .append("\" x2=\"725\" y2=\"").append(baseline)
                .append("\" stroke=\"#333\"/>\n");
        for (int index = 0; index < summaries.size(); index++) {
            Summary summary = summaries.get(index);
            int x = 100 + index * 160;
            int barHeight = (int) Math.round(summary.meanCompletionMillis() / maximum * 260);
            int y = baseline - barHeight;
            String color = summary.condition().policy() == SchedulingPolicy.FIFO
                    ? "#5b8ff9"
                    : "#61dDAa";
            svg.append("  <rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"95\" height=\"").append(barHeight)
                    .append("\" fill=\"").append(color).append("\"/>\n")
                    .append("  <text x=\"").append(x + 47).append("\" y=\"").append(y - 8)
                    .append("\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"13\">")
                    .append(format(summary.meanCompletionMillis())).append(" ms</text>\n")
                    .append("  <text x=\"").append(x + 47).append("\" y=\"375\" ")
                    .append("text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">")
                    .append(shortPolicy(summary.condition().policy())).append(", ")
                    .append(summary.condition().workers()).append(" worker")
                    .append(summary.condition().workers() == 1 ? "" : "s")
                    .append("</text>\n");
        }
        svg.append("  <text x=\"20\" y=\"220\" transform=\"rotate(-90 20 220)\" ")
                .append("text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"13\">")
                .append("milliseconds</text>\n</svg>\n");
        Files.writeString(path, svg, StandardCharsets.UTF_8);
    }

    private static void printSummaries(List<Summary> summaries) {
        System.out.println("policy, workers, success, failure, mean ms, p95 ms, docs/min");
        for (Summary summary : summaries) {
            System.out.printf(
                    Locale.ROOT,
                    "%s, %d, %d, %d, %.3f, %.3f, %.3f%n",
                    summary.condition().policy(),
                    summary.condition().workers(),
                    summary.successfulJobs(),
                    summary.failedJobs(),
                    summary.meanCompletionMillis(),
                    summary.p95CompletionMillis(),
                    summary.documentsPerMinute());
        }
    }

    private static Map<String, Path> samplePaths(Path directory) {
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("long", directory.resolve("scanned-long.pdf"));
        paths.put("medium", directory.resolve("scanned-medium.pdf"));
        paths.put("small", directory.resolve("scanned-small.pdf"));
        return paths;
    }

    private static int positiveInteger(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double durationMillis(java.time.Instant start, java.time.Instant end) {
        return Duration.between(start, end).toNanos() / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String csvValue(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String shortPolicy(SchedulingPolicy policy) {
        return policy == SchedulingPolicy.FIFO ? "FIFO" : "estimated SJF";
    }

    record Condition(SchedulingPolicy policy, int workers) {
    }

    record Measurement(
            int runOrder,
            int repetition,
            Condition condition,
            String documentId,
            int pageCount,
            JobStatus status,
            String submittedAt,
            String startedAt,
            String finishedAt,
            double queueWaitMillis,
            double runTimeMillis,
            double completionLatencyMillis,
            double wallMillis,
            String errorMessage) {

        static Measurement from(
                int runOrder,
                int repetition,
                Condition condition,
                double wallMillis,
                JobSnapshot job) {
            return new Measurement(
                    runOrder,
                    repetition,
                    condition,
                    job.documentId(),
                    job.pageCount(),
                    job.status(),
                    job.submittedAt().toString(),
                    job.startedAt().toString(),
                    job.finishedAt().toString(),
                    durationMillis(job.submittedAt(), job.startedAt()),
                    durationMillis(job.startedAt(), job.finishedAt()),
                    durationMillis(job.submittedAt(), job.finishedAt()),
                    wallMillis,
                    job.errorMessage());
        }
    }

    record Summary(
            Condition condition,
            int successfulJobs,
            int failedJobs,
            double meanCompletionMillis,
            double medianCompletionMillis,
            double p95CompletionMillis,
            double maxQueueWaitMillis,
            double smallMeanMillis,
            double mediumMeanMillis,
            double longMeanMillis,
            double documentsPerMinute,
            double pagesPerMinute,
            double totalWallMillis) {
    }
}
