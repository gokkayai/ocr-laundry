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
