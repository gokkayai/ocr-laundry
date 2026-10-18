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

The application prints `ocr-laundry starting` and exits.

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
