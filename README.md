# ocr-laundry

ocr-laundry is a Java service for comparing scheduling policies while processing
scanned documents with OCR.

## Development toolchain

This project uses Java/Javac 25 and Maven 3.9.11. With SDKMAN installed, run
`sdk env install` once and `sdk env` when entering the project.

Build and run the application from the command line:

```shell
./mvnw clean verify
java -jar target/ocr-laundry.jar
```

The application prints its available commands. To regenerate the fixed benchmark PDFs and run
the FIFO/estimated-SJF experiment:

```shell
java -jar target/ocr-laundry.jar generate-samples samples
java -jar target/ocr-laundry.jar experiment samples docs/experiment-results 3
```

See [`docs/experiments.md`](docs/experiments.md) for the method, measured results, plot,
limitations, and environment details.

## HTTP API

Start the local API with FIFO scheduling and two OCR workers:

```shell
java -jar target/ocr-laundry.jar serve 8080 samples fifo 2
```

The service listens on `127.0.0.1` and accepts only the sample document IDs returned by
`GET /api/documents`. See [`docs/api.md`](docs/api.md) for endpoints and a complete `curl`
workflow.

Open [http://127.0.0.1:8080](http://127.0.0.1:8080) while the server is running to use the
dashboard. It submits real API jobs, polls their status without overlapping requests, exposes
queue and latency metrics, supports cancellation, and links to the static benchmark evidence.

## OCR requirements

Install Tesseract with the English language data. Verify the installation with:

```shell
sudo dnf install tesseract tesseract-langpack-eng
tesseract --version
tesseract --list-langs
```

To check Tesseract manually on an image, run:

```shell
tesseract input.png stdout -l eng
```

The OCR runner renders scanned PDF pages at 200 DPI and processes them in page
order. The default limit is 25 MB, 100 pages, and two minutes per document.

## Docker

The container includes Java 25, Tesseract and its English language data. Build and run it with
explicit resource limits and a read-only root filesystem:

```shell
docker build -t ocr-laundry .
docker run --rm \
  --cpus 1 \
  --memory 768m \
  --pids-limit 128 \
  --read-only \
  --tmpfs /app/tmp:rw,noexec,nosuid,size=256m,mode=1777 \
  --security-opt no-new-privileges \
  -p 127.0.0.1:8080:8080 \
  ocr-laundry
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). To choose estimated SJF or another worker
count, append the complete server command after the image name, for example:

```shell
docker run --rm -p 127.0.0.1:8080:8080 ocr-laundry serve 8080 /app/samples sjf 1
```

Run `./scripts/docker-smoke-test.sh` to build the image and process a real one-page PDF with the
same CPU, memory, PID and filesystem restrictions used above. It uses Docker by default; run
`CONTAINER_RUNTIME=podman ./scripts/docker-smoke-test.sh` to use Podman instead.

`OCR_LAUNDRY_HOST` controls the server bind address. It defaults to `127.0.0.1` for local Java
runs; the image sets it to `0.0.0.0` so published container ports work.

## Continuous integration and hosting

GitHub Actions compiles the application, runs the Java tests with Tesseract installed, checks the
dashboard in Chromium, then builds the container and performs a real OCR smoke test under resource
limits. Full benchmark runs remain the separate documented experiment command because they are too
heavy and variable for a pull-request check.

The repository and its files under `docs/experiment-results` are the publishable source and static
experimental evidence. No public hosted endpoint is configured. If the image is deployed later,
the platform must provide at least 768 MiB of memory and a writable `/app/tmp`; TLS and access
control belong at the hosting layer. Jobs and OCR results are intentionally in memory, so a restart
or free-host spin-down discards them. Static benchmark pages are historical evidence, not live OCR.
