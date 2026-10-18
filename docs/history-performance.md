# Bounded job history

The scheduler retains at most 100 terminal records in completion order, plus active jobs.
With the default two workers and pending capacity of 20, at most 122 job records are retained.
Eviction releases references to full OCR text and metadata; it does not persist them elsewhere.
Metrics and the plain jobs endpoint describe retained history. Evicted IDs return 404.

The dashboard fetches all active jobs and 20 recently submitted terminal jobs. Conditional
requests return HTTP 304 with no body for unchanged lists, and the browser skips rebuilding
the job cards. The poll interval remains 1.5 seconds; metrics and health still refresh.
Selection, serialization, and latency aggregation operate on bounded history.

## Measurement

Run `./mvnw -Dtest=OcrLaundryServerTest test` to reproduce. The regression test processes
1,000 sequential jobs with a deterministic fake OCR runner and short output, using the actual
HTTP serializer. This isolates history behavior from Tesseract performance. It reconstructs
the former full-history JSON payload by accumulating each completed job response plus array
separators; the previous unbounded server is not run as a separate process.

Observed on 2026-10-19:

| Measure | Full-history baseline | Bounded dashboard |
| --- | ---: | ---: |
| Finished records retained after 1,000 jobs | 1,000 | 100 |
| Jobs in an idle dashboard response | 1,000 | 20 |
| JSON response body | 390,007 bytes | 7,810 bytes |
| Unchanged conditional response body | Full response | 0 bytes (304) |

This is a 98.0% response-body reduction and a 90% retained-record reduction for this workload.
Timestamps and duration formatting can slightly change byte counts between runs. HTTP headers
are excluded. These are not measured heap-memory savings or OCR throughput improvements.
Record count is bounded, but individual full OCR outputs still vary in size.

Tests also cover changed responses invalidating ETags and cancellation-driven eviction
preserving older active jobs.
