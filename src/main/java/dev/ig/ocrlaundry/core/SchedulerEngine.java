package dev.ig.ocrlaundry.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

import dev.ig.ocrlaundry.docs.DocumentCatalog;
import dev.ig.ocrlaundry.model.DocumentInfo;
import dev.ig.ocrlaundry.model.JobRecord;
import dev.ig.ocrlaundry.model.JobSnapshot;
import dev.ig.ocrlaundry.model.JobSpec;
import dev.ig.ocrlaundry.model.SchedulingPolicy;
import dev.ig.ocrlaundry.ocr.OcrResult;
import dev.ig.ocrlaundry.ocr.OcrRunner;

public final class SchedulerEngine implements AutoCloseable {
    public static final int DEFAULT_PENDING_CAPACITY = 20;

    private final Object stateLock = new Object();
    private final DocumentCatalog documentCatalog;
    private final OcrRunner ocrRunner;
    private final Clock clock;
    private final int pendingCapacity;
    private final PriorityQueue<JobRecord> pendingJobs;
    private final Map<UUID, JobRecord> jobs = new LinkedHashMap<>();
    private final Thread worker;

    private long nextSubmittedSequence;
    private int runningCount;
    private boolean acceptingSubmissions = true;
    private boolean shutdownRequested;

    public SchedulerEngine(DocumentCatalog documentCatalog, OcrRunner ocrRunner) {
        this(
                documentCatalog,
                ocrRunner,
                SchedulingPolicy.FIFO,
                DEFAULT_PENDING_CAPACITY,
                Clock.systemUTC());
    }

    public SchedulerEngine(
            DocumentCatalog documentCatalog,
            OcrRunner ocrRunner,
            SchedulingPolicy policy,
            int pendingCapacity,
            Clock clock) {
        this.documentCatalog = Objects.requireNonNull(documentCatalog, "documentCatalog");
        this.ocrRunner = Objects.requireNonNull(ocrRunner, "ocrRunner");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        this.pendingCapacity = pendingCapacity;
        pendingJobs = new PriorityQueue<>(QueueOrdering.forPolicy(policy));
        worker = new Thread(this::workerLoop, "ocr-laundry-worker");
        worker.start();
    }

    public JobSnapshot submit(JobSpec spec) {
        Objects.requireNonNull(spec, "spec");
        DocumentInfo document = documentCatalog.require(spec.documentId());

        synchronized (stateLock) {
            if (!acceptingSubmissions) {
                throw new IllegalStateException("Scheduler is shutting down");
            }
            if (pendingJobs.size() >= pendingCapacity) {
                throw new QueueFullException(pendingCapacity);
            }

            JobRecord job = new JobRecord(
                    UUID.randomUUID(),
                    spec,
                    document,
                    nextSubmittedSequence++,
                    clock.instant());
            jobs.put(job.id(), job);
            pendingJobs.add(job);
            JobSnapshot submittedJob = job.snapshot();
            stateLock.notifyAll();
            return submittedJob;
        }
    }

    public Optional<JobSnapshot> find(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        synchronized (stateLock) {
            JobRecord job = jobs.get(jobId);
            return job == null ? Optional.empty() : Optional.of(job.snapshot());
        }
    }

    public List<JobSnapshot> jobs() {
        synchronized (stateLock) {
            List<JobSnapshot> snapshots = new ArrayList<>(jobs.size());
            for (JobRecord job : jobs.values()) {
                snapshots.add(job.snapshot());
            }
            return snapshots;
        }
    }

    public int pendingCount() {
        synchronized (stateLock) {
            return pendingJobs.size();
        }
    }

    public int runningCount() {
        synchronized (stateLock) {
            return runningCount;
        }
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }

        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (stateLock) {
            while (!pendingJobs.isEmpty() || runningCount > 0) {
                if (remainingNanos <= 0) {
                    return false;
                }
                long millis = remainingNanos / 1_000_000;
                int nanos = (int) (remainingNanos % 1_000_000);
                stateLock.wait(millis, nanos);
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            acceptingSubmissions = false;
            shutdownRequested = true;
            stateLock.notifyAll();
        }

        if (Thread.currentThread() == worker) {
            return;
        }

        try {
            worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerLoop() {
        while (true) {
            JobRecord job;
            synchronized (stateLock) {
                while (pendingJobs.isEmpty() && !shutdownRequested) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (pendingJobs.isEmpty() && shutdownRequested) {
                    return;
                }

                job = pendingJobs.remove();
                job.markRunning(clock.instant());
                runningCount++;
                stateLock.notifyAll();
            }

            runJob(job);
        }
    }

    private void runJob(JobRecord job) {
        OcrResult result = null;
        String unexpectedError = null;
        try {
            result = ocrRunner.run(job.document().path());
            if (result == null) {
                unexpectedError = "OCR runner returned no result";
            }
        } catch (RuntimeException exception) {
            unexpectedError = exceptionMessage(exception);
        }

        synchronized (stateLock) {
            if (unexpectedError == null) {
                job.markFinished(result, clock.instant());
            } else {
                job.markFailed(unexpectedError, clock.instant());
            }
            runningCount--;
            stateLock.notifyAll();
        }
    }

    private static String exceptionMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
