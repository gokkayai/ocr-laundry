package dev.ig.ocrlaundry.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
import dev.ig.ocrlaundry.model.JobStatus;
import dev.ig.ocrlaundry.model.SchedulingPolicy;
import dev.ig.ocrlaundry.ocr.OcrResult;
import dev.ig.ocrlaundry.ocr.OcrRunner;

public final class SchedulerEngine implements AutoCloseable {
    public static final int DEFAULT_PENDING_CAPACITY = 20;
    public static final int DEFAULT_WORKER_COUNT = 2;

    private final Object stateLock = new Object();
    private final DocumentCatalog documentCatalog;
    private final OcrRunner ocrRunner;
    private final Clock clock;
    private final SchedulingPolicy schedulingPolicy;
    private final int pendingCapacity;
    private final PriorityQueue<JobRecord> pendingJobs;
    private final Map<UUID, JobRecord> jobs = new LinkedHashMap<>();
    private final Map<UUID, CancellationToken> runningTokens = new HashMap<>();
    private final List<Thread> workers;

    private long nextSubmittedSequence;
    private int runningCount;
    private boolean acceptingSubmissions = true;
    private boolean dispatchStarted;
    private boolean shutdownRequested;

    public SchedulerEngine(DocumentCatalog documentCatalog, OcrRunner ocrRunner) {
        this(
                documentCatalog,
                ocrRunner,
                SchedulingPolicy.FIFO,
                DEFAULT_PENDING_CAPACITY,
                DEFAULT_WORKER_COUNT,
                Clock.systemUTC());
    }

    public SchedulerEngine(DocumentCatalog documentCatalog, OcrRunner ocrRunner, int workerCount) {
        this(
                documentCatalog,
                ocrRunner,
                SchedulingPolicy.FIFO,
                DEFAULT_PENDING_CAPACITY,
                workerCount,
                Clock.systemUTC());
    }

    public SchedulerEngine(
            DocumentCatalog documentCatalog,
            OcrRunner ocrRunner,
            SchedulingPolicy policy,
            int workerCount) {
        this(
                documentCatalog,
                ocrRunner,
                policy,
                DEFAULT_PENDING_CAPACITY,
                workerCount,
                Clock.systemUTC());
    }

    public SchedulerEngine(
            DocumentCatalog documentCatalog,
            OcrRunner ocrRunner,
            SchedulingPolicy policy,
            int pendingCapacity,
            int workerCount,
            Clock clock) {
        this(documentCatalog, ocrRunner, policy, pendingCapacity, workerCount, clock, true);
    }

    private SchedulerEngine(
            DocumentCatalog documentCatalog,
            OcrRunner ocrRunner,
            SchedulingPolicy policy,
            int pendingCapacity,
            int workerCount,
            Clock clock,
            boolean dispatchStarted) {
        this.documentCatalog = Objects.requireNonNull(documentCatalog, "documentCatalog");
        this.ocrRunner = Objects.requireNonNull(ocrRunner, "ocrRunner");
        this.clock = Objects.requireNonNull(clock, "clock");
        schedulingPolicy = Objects.requireNonNull(policy, "policy");
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        this.pendingCapacity = pendingCapacity;
        this.dispatchStarted = dispatchStarted;
        pendingJobs = new PriorityQueue<>(QueueOrdering.forPolicy(schedulingPolicy));
        workers = new ArrayList<>(workerCount);
        for (int workerNumber = 1; workerNumber <= workerCount; workerNumber++) {
            Thread worker = new Thread(
                    this::workerLoop,
                    "ocr-laundry-worker-" + workerNumber);
            workers.add(worker);
            worker.start();
        }
    }

    public static SchedulerEngine paused(
            DocumentCatalog documentCatalog,
            OcrRunner ocrRunner,
            SchedulingPolicy policy,
            int workerCount) {
        return new SchedulerEngine(
                documentCatalog,
                ocrRunner,
                policy,
                DEFAULT_PENDING_CAPACITY,
                workerCount,
                Clock.systemUTC(),
                false);
    }

    public void start() {
        synchronized (stateLock) {
            if (shutdownRequested) {
                throw new IllegalStateException("Scheduler is shutting down");
            }
            dispatchStarted = true;
            stateLock.notifyAll();
        }
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

    public boolean cancel(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");

        synchronized (stateLock) {
            JobRecord job = jobs.get(jobId);
            if (job == null) {
                return false;
            }

            if (job.status() == JobStatus.QUEUED) {
                pendingJobs.remove(job);
                job.markCancelled(clock.instant());
                stateLock.notifyAll();
                return true;
            }

            if (job.status() != JobStatus.RUNNING) {
                return false;
            }
            runningTokens.get(jobId).cancel();
            return true;
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

    public int workerCount() {
        return workers.size();
    }

    public SchedulingPolicy schedulingPolicy() {
        return schedulingPolicy;
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
            dispatchStarted = true;
            shutdownRequested = true;
            stateLock.notifyAll();
        }

        for (Thread worker : workers) {
            if (Thread.currentThread() == worker) {
                continue;
            }
            try {
                worker.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void workerLoop() {
        while (true) {
            JobRecord job;
            CancellationToken cancellationToken;
            synchronized (stateLock) {
                while ((!dispatchStarted || pendingJobs.isEmpty()) && !shutdownRequested) {
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
                cancellationToken = new CancellationToken();
                runningTokens.put(job.id(), cancellationToken);
                runningCount++;
                stateLock.notifyAll();
            }

            runJob(job, cancellationToken);
        }
    }

    private void runJob(JobRecord job, CancellationToken cancellationToken) {
        OcrResult result = null;
        String unexpectedError = null;
        try {
            result = ocrRunner.run(job.document().path(), cancellationToken);
            if (result == null) {
                unexpectedError = "OCR runner returned no result";
            }
        } catch (RuntimeException exception) {
            unexpectedError = exceptionMessage(exception);
        }

        synchronized (stateLock) {
            if (cancellationToken.isCancellationRequested()) {
                job.markCancelled(clock.instant());
            } else if (unexpectedError == null) {
                job.markFinished(result, clock.instant());
            } else {
                job.markFailed(unexpectedError, clock.instant());
            }
            runningTokens.remove(job.id());
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
