FROM docker.io/library/maven:3.9.11-eclipse-temurin-25-noble AS build

WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
COPY docs/experiment-results ./docs/experiment-results
RUN mvn --batch-mode --define skipTests package

FROM docker.io/library/eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl tesseract-ocr tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system ocrlaundry \
    && useradd --system --gid ocrlaundry --home-dir /app --shell /usr/sbin/nologin ocrlaundry

WORKDIR /app
COPY --from=build --chown=ocrlaundry:ocrlaundry /workspace/target/ocr-laundry.jar ./ocr-laundry.jar
COPY --chown=ocrlaundry:ocrlaundry samples ./samples
RUN mkdir /app/tmp && chown ocrlaundry:ocrlaundry /app/tmp

USER ocrlaundry
ENV OCR_LAUNDRY_HOST=0.0.0.0
ENV JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/app/tmp -XX:MaxRAMPercentage=70"

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/ocr-laundry.jar"]
CMD ["serve", "8080", "/app/samples", "fifo", "2"]
