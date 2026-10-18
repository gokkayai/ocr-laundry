package dev.ig.ocrlaundry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.ig.ocrlaundry.docs.DocumentCatalog;
import dev.ig.ocrlaundry.docs.PdfInspector;
import dev.ig.ocrlaundry.model.JobSnapshot;
import dev.ig.ocrlaundry.model.JobSpec;
import dev.ig.ocrlaundry.model.JobStatus;
import dev.ig.ocrlaundry.ocr.OcrResult;
import dev.ig.ocrlaundry.ocr.OcrRunner;

class SchedulerEngineTest {
    @TempDir
    Path tempDirectory;

    @Test
    void processesTenKnownDocumentsInFifoOrderAndRecordsTransitions() throws Exception {
        DocumentCatalog catalog = createCatalog(10);
        BlockingFirstRunner runner = new BlockingFirstRunner();
        SchedulerEngine engine = new SchedulerEngine(catalog, runner);

        try {
            List<JobSnapshot> submitted = new ArrayList<>();
            submitted.add(engine.submit(new JobSpec("document-0", 50)));
            assertTrue(runner.awaitFirstStarted());

            for (int index = 1; index < 10; index++) {
                submitted.add(engine.submit(new JobSpec("document-" + index, 50 - index)));
            }

            JobSnapshot running = engine.find(submitted.getFirst().id()).orElseThrow();
            assertEquals(JobStatus.RUNNING, running.status());
            assertNotNull(running.startedAt());
            assertNull(running.finishedAt());
            assertEquals(1, engine.runningCount());
            assertEquals(9, engine.pendingCount());

            for (int index = 1; index < submitted.size(); index++) {
                JobSnapshot queued = engine.find(submitted.get(index).id()).orElseThrow();
                assertEquals(JobStatus.QUEUED, queued.status());
                assertNull(queued.startedAt());
                assertNull(queued.finishedAt());
            }

            runner.releaseFirst();
            assertTrue(engine.awaitIdle(Duration.ofSeconds(10)));

            List<JobSnapshot> completed = engine.jobs();
            assertEquals(10, completed.size());
            for (int index = 0; index < completed.size(); index++) {
                JobSnapshot job = completed.get(index);
                assertEquals(index, job.submittedSequence());
                assertEquals(JobStatus.COMPLETED, job.status());
                assertNotNull(job.submittedAt());
                assertNotNull(job.startedAt());
                assertNotNull(job.finishedAt());
                assertFalse(job.startedAt().isBefore(job.submittedAt()));
                assertFalse(job.finishedAt().isBefore(job.startedAt()));
                assertEquals(job.pageCount(), job.estimatedCost());
                assertEquals("", job.errorMessage());
            }

            List<String> expectedOrder = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                expectedOrder.add("document-" + index + ".pdf");
            }
            assertEquals(expectedOrder, runner.executionOrder());
        } finally {
            runner.releaseFirst();
            engine.close();
        }
    }

    @Test
    void rejectsSubmissionWhenTwentyJobsArePending() throws Exception {
        DocumentCatalog catalog = createCatalog(1);
        BlockingFirstRunner runner = new BlockingFirstRunner();
        SchedulerEngine engine = new SchedulerEngine(catalog, runner);

        try {
            engine.submit(new JobSpec("document-0", 0));
            assertTrue(runner.awaitFirstStarted());
            for (int index = 0; index < SchedulerEngine.DEFAULT_PENDING_CAPACITY; index++) {
                engine.submit(new JobSpec("document-0", index));
            }

            assertEquals(SchedulerEngine.DEFAULT_PENDING_CAPACITY, engine.pendingCount());
            assertThrows(
                    QueueFullException.class,
                    () -> engine.submit(new JobSpec("document-0", 999)));
        } finally {
            runner.releaseFirst();
            assertTrue(engine.awaitIdle(Duration.ofSeconds(10)));
            engine.close();
        }
    }

    @Test
    void recordsOcrFailure() throws Exception {
        DocumentCatalog catalog = createCatalog(1);
        OcrRunner failingRunner = path -> OcrResult.failure(
                1,
                Duration.ofMillis(5),
                "simulated OCR failure");

        try (SchedulerEngine engine = new SchedulerEngine(catalog, failingRunner)) {
            JobSnapshot submitted = engine.submit(new JobSpec("document-0", 0));
            assertTrue(engine.awaitIdle(Duration.ofSeconds(5)));

            JobSnapshot failed = engine.find(submitted.id()).orElseThrow();
            assertEquals(JobStatus.FAILED, failed.status());
            assertEquals("simulated OCR failure", failed.errorMessage());
            assertNotNull(failed.startedAt());
            assertNotNull(failed.finishedAt());
        }
    }

    @Test
    void rejectsUnknownDocumentId() throws Exception {
        DocumentCatalog catalog = createCatalog(1);

        try (SchedulerEngine engine = new SchedulerEngine(
                catalog,
                path -> OcrResult.success(1, "text", Duration.ZERO))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> engine.submit(new JobSpec("unknown", 0)));
        }
    }

    private DocumentCatalog createCatalog(int documentCount) throws IOException {
        Map<String, Path> paths = new LinkedHashMap<>();
        for (int index = 0; index < documentCount; index++) {
            Path path = tempDirectory.resolve("document-" + index + ".pdf");
            try (PDDocument document = new PDDocument()) {
                int pageCount = index % 3 + 1;
                for (int page = 0; page < pageCount; page++) {
                    document.addPage(new PDPage());
                }
                document.save(path.toFile());
            }
            paths.put("document-" + index, path);
        }
        return new DocumentCatalog(paths, new PdfInspector());
    }

    private static final class BlockingFirstRunner implements OcrRunner {
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final List<String> executionOrder = new CopyOnWriteArrayList<>();

        @Override
        public OcrResult run(Path pdfPath) {
            executionOrder.add(pdfPath.getFileName().toString());
            if (first.compareAndSet(true, false)) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        return OcrResult.failure(1, Duration.ZERO, "test runner timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return OcrResult.failure(1, Duration.ZERO, "test runner interrupted");
                }
            }
            return OcrResult.success(1, pdfPath.getFileName().toString(), Duration.ZERO);
        }

        boolean awaitFirstStarted() throws InterruptedException {
            return firstStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseFirst() {
            releaseFirst.countDown();
        }

        List<String> executionOrder() {
            return List.copyOf(executionOrder);
        }
    }
}
