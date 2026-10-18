"use strict";

async function loadBenchmark() {
  const message = document.querySelector("#benchmark-finding");
  try {
    const response = await fetch("/benchmark/summary.csv", { cache: "no-store" });
    if (!response.ok) throw new Error("Benchmark data unavailable");
    // The experiment writer emits a numeric summary with unquoted column names.
    const [header, ...lines] = (await response.text()).trim().split(/\r?\n/);
    const columns = header.split(",");
    const rows = lines.map(line => Object.fromEntries(line.split(",").map((value, index) =>
      [columns[index], columns[index] === "policy" ? value : Number(value)])));
    const required = ["workers", "successful_jobs", "failed_jobs", "mean_completion_ms"];
    if (!rows.length || rows.some(row => required.some(key => !Number.isFinite(row[key]) || row[key] < 0))) {
      throw new Error("Invalid benchmark data");
    }
    const successes = rows.reduce((sum, row) => sum + row.successful_jobs, 0);
    const failures = rows.reduce((sum, row) => sum + row.failed_jobs, 0);
    const comparisons = [];
    for (const workers of [...new Set(rows.map(row => row.workers))].sort((a, b) => a - b)) {
      const fifo = rows.filter(row => row.workers === workers && row.policy === "FIFO");
      const sjf = rows.filter(row => row.workers === workers && row.policy === "ESTIMATED_SJF");
      if (fifo.length !== 1 || sjf.length !== 1) continue;
      const baseline = fifo[0];
      const candidate = sjf[0];
      if (baseline.mean_completion_ms <= 0 || !baseline.successful_jobs || !candidate.successful_jobs) continue;
      if (baseline.successful_jobs !== candidate.successful_jobs || baseline.failed_jobs || candidate.failed_jobs) {
        comparisons.push(`${workers} worker(s): outcomes differ or include failures; no headline comparison.`);
        continue;
      }
      const change = (baseline.mean_completion_ms - candidate.mean_completion_ms) / baseline.mean_completion_ms * 100;
      const direction = change >= 0 ? "lower" : "higher";
      comparisons.push(`Estimated SJF mean latency was ${Math.abs(change).toFixed(1)}% ${direction} with ${workers} worker(s).`);
    }
    message.textContent = `${comparisons.join(" ") || "No comparable policy pairs available."} ${successes} successful jobs; ${failures} failures.`;
    document.querySelector("#observations").textContent = String(successes + failures);
    document.querySelector("#conditions").textContent = String(rows.length);
    document.querySelector("#failures").textContent = String(failures);
  } catch (error) {
    message.textContent = `Results unavailable: ${error.message}.`;
  }
}

loadBenchmark();
