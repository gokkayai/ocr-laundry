package dev.ig.ocrlaundry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.PriorityQueue;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.ig.ocrlaundry.model.DocumentInfo;
import dev.ig.ocrlaundry.model.JobRecord;
import dev.ig.ocrlaundry.model.JobSpec;
import dev.ig.ocrlaundry.model.SchedulingPolicy;

class QueueOrderingTest {
    @Test
    void fifoUsesSubmissionSequenceAndIgnoresPriority() {
        DocumentInfo document = new DocumentInfo("document", Path.of("document.pdf"), 1);
        JobRecord later = new JobRecord(
                UUID.randomUUID(),
                new JobSpec("document", 100),
                document,
                2,
                Instant.EPOCH.plusSeconds(2));
        JobRecord earlier = new JobRecord(
                UUID.randomUUID(),
                new JobSpec("document", -100),
                document,
                1,
                Instant.EPOCH.plusSeconds(1));
        PriorityQueue<JobRecord> queue = new PriorityQueue<>(
                QueueOrdering.forPolicy(SchedulingPolicy.FIFO));

        queue.add(later);
        queue.add(earlier);

        assertEquals(1, queue.remove().submittedSequence());
        assertEquals(2, queue.remove().submittedSequence());
    }

    @Test
    void estimatedSjfUsesPageCountThenSubmissionSequence() {
        JobRecord longJob = job(0, 8);
        JobRecord laterShortJob = job(2, 1);
        JobRecord earlierShortJob = job(1, 1);
        PriorityQueue<JobRecord> queue = new PriorityQueue<>(
                QueueOrdering.forPolicy(SchedulingPolicy.ESTIMATED_SJF));

        queue.add(longJob);
        queue.add(laterShortJob);
        queue.add(earlierShortJob);

        assertEquals(1, queue.remove().submittedSequence());
        assertEquals(2, queue.remove().submittedSequence());
        assertEquals(0, queue.remove().submittedSequence());
    }

    private static JobRecord job(long sequence, int pageCount) {
        DocumentInfo document = new DocumentInfo(
                "document-" + sequence,
                Path.of("document-" + sequence + ".pdf"),
                pageCount);
        return new JobRecord(
                UUID.randomUUID(),
                new JobSpec(document.id(), 0),
                document,
                sequence,
                Instant.EPOCH.plusSeconds(sequence));
    }
}
