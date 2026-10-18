package dev.ig.ocrlaundry.core;

public final class QueueFullException extends RuntimeException {
    public QueueFullException(int capacity) {
        super("Pending job queue is full; capacity is " + capacity);
    }
}
