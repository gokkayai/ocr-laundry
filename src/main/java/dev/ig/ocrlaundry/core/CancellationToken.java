package dev.ig.ocrlaundry.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final CountDownLatch cancellationSignal = new CountDownLatch(1);

    public boolean cancel() {
        boolean changed = cancellationRequested.compareAndSet(false, true);
        if (changed) {
            cancellationSignal.countDown();
        }
        return changed;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    public boolean awaitCancellation(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        return cancellationSignal.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
