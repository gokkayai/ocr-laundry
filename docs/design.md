# Scheduler design

- `SchedulerEngine` owns the pending queue and all mutable job state.
- Queue changes, job transitions, and worker claims happen while holding one
  state lock.
- Workers release the state lock before calling `OcrRunner`.
- The pending capacity excludes jobs that a worker has already claimed.
- A pending cancellation removes the job before marking it `CANCELLED`.
- A running cancellation stays `RUNNING` until OCR stops and cleanup finishes.
- OCR runners receive a cancellation token and must check it during long work.
- Shutdown rejects new submissions, drains accepted work, and wakes idle workers.
- Scheduling policy and worker count are fixed for the lifetime of an engine.
- FIFO orders waiting jobs by submission sequence.
- Estimated SJF orders waiting jobs by page count, then submission sequence.
- Page count is only a rough ranking cost, not a predicted OCR duration.
- Scheduling is non-preemptive: a newly submitted short job does not interrupt
  work that has already started.
- A steady stream of short jobs can starve a long job under estimated SJF;
  fairness and aging are deferred extensions.
