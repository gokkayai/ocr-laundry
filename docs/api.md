# HTTP API

Finished history is bounded to 100 jobs (evicted in completion order), plus all active jobs.
Eviction releases the stored OCR result and metadata; evicted IDs return 404. This is not durable
storage. Metrics describe retained history, not lifetime totals.
`GET /api/jobs?view=dashboard` returns all active jobs plus the 20 most recently submitted finished
jobs still retained. Plain `GET /api/jobs` returns all retained jobs. Both support `ETag` and
`If-None-Match`, returning a bodyless 304 when unchanged. The dashboard skips rendering on 304.

The API is a local, in-memory interface to the scheduler. It does not accept filesystem paths or
PDF uploads. Only the documents listed by `GET /api/documents` can be submitted.

## Start the server

Build the runnable JAR, then choose the port, sample directory, scheduling policy, and worker
count:

```bash
./mvnw clean package
java -jar target/ocr-laundry.jar serve 8080 samples fifo 2
```

Use `sjf` instead of `fifo` to run the estimated shortest-job-first policy. The server binds to
`127.0.0.1`; press Ctrl+C to stop it cleanly.

Open `http://127.0.0.1:8080` in a browser for the live dashboard, or continue with the commands
below to use the API directly.

## Endpoints

| Method and path | Result |
| --- | --- |
| `GET /health` | Process status and availability of Tesseract with English language data |
| `GET /api/documents` | Allowlisted document IDs and page counts |
| `POST /api/jobs` | Submit a document job |
| `GET /api/jobs` | List all job snapshots |
| `GET /api/jobs/{id}` | Get one job, OCR preview, error, and timings |
| `DELETE /api/jobs/{id}` | Request queued or running-job cancellation |
| `GET /api/metrics` | Queue, worker, outcome, and completion-latency aggregates |

## Curl workflow

Check dependencies and list the valid document IDs:

```bash
curl -i http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8080/api/documents
```

Submit a job. The response is `202 Accepted` and includes a `Location` header containing its URL:

```bash
curl -i \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"small","priority":5}' \
  http://127.0.0.1:8080/api/jobs
```

Copy the UUID from the `Location` header into the following commands:

```bash
curl -i http://127.0.0.1:8080/api/jobs/REPLACE_WITH_JOB_ID
curl -i -X DELETE http://127.0.0.1:8080/api/jobs/REPLACE_WITH_JOB_ID
```

Inspect all jobs and aggregate metrics:

```bash
curl -i http://127.0.0.1:8080/api/jobs
curl -i http://127.0.0.1:8080/api/metrics
```

## Status and errors

Job status is one of `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, or `CANCELLED`. A failed OCR job
remains inspectable and includes its message in the `error` field. Timestamp-dependent duration
fields are `null` until the required transition occurs.

Errors use `{"error":"message"}` and an appropriate HTTP status:

- `400` for malformed JSON, invalid UUIDs, or unknown document IDs;
- `404` for unknown jobs;
- `409` when a completed, failed, or cancelled job cannot be cancelled;
- `415` when a submission is not JSON;
- `429` when the bounded pending queue is full;
- `503` when OCR dependencies are unavailable or the scheduler is shutting down.

Request bodies are capped at 16 KiB. OCR text is returned only as a 300-character preview, and
document filesystem paths are never included in responses.
