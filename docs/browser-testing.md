# Browser verification

Build the JAR, install the pinned development dependencies, and run the browser tests:

```bash
./mvnw package
npm ci
npm run test:browser
```

Tests use installed Google Chrome at `/usr/bin/google-chrome`. Set `CHROME_PATH` to another
Chrome/Chromium executable if needed. Tesseract and English data must be installed.
Playwright starts and stops its own server on port 18082; leave that port free.
These Node dependencies are only for development, not the Java runtime.

The suite checks real OCR submission and preview rendering, JavaScript errors, horizontal
overflow, and benchmark image loading at 1440×1000 and 390×844. It captures full-page dashboard
and benchmark screenshots under `test-results/`, which is ignored by Git. Screenshots are
review artifacts, not pixel-diff baselines. This covers Chrome desktop/mobile viewport layouts,
not every browser or physical mobile device.

An additional test replaces the summary response to verify that headlines update when the
data changes, label a regression as higher latency, suppress comparisons with failed/unequal
outcomes, and show an unavailable state for malformed input.

Benchmark counts and percentages come from `/benchmark/summary.csv`. Regenerate experiment
outputs and rebuild the JAR to publish new data; no headline edits are needed. The plot remains
the SVG generated in the same experiment run. Serve matching CSV/SVG artifacts together.
