package dev.ig.ocrlaundry.core;

import java.util.Comparator;
import java.util.Objects;

import dev.ig.ocrlaundry.model.JobRecord;
import dev.ig.ocrlaundry.model.SchedulingPolicy;

public final class QueueOrdering {
    private QueueOrdering() {
    }

    public static Comparator<JobRecord> forPolicy(SchedulingPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return switch (policy) {
            case FIFO -> Comparator.comparingLong(JobRecord::submittedSequence);
            case ESTIMATED_SJF -> Comparator
                    .comparingInt(JobRecord::estimatedCost)
                    .thenComparingLong(JobRecord::submittedSequence);
        };
    }
}
