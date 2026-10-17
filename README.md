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
