"use strict";

const POLL_DELAY_MS = 1500;

const elements = {
  form: document.querySelector("#job-form"),
  documentSelect: document.querySelector("#document-select"),
  priorityInput: document.querySelector("#priority-input"),
  submitButton: document.querySelector("#submit-button"),
  formMessage: document.querySelector("#form-message"),
  healthPill: document.querySelector("#health-pill"),
  healthText: document.querySelector("#health-text"),
  policyBadge: document.querySelector("#policy-badge"),
  activeWorkers: document.querySelector("#active-workers"),
  workerDetail: document.querySelector("#worker-detail"),
  pendingJobs: document.querySelector("#pending-jobs"),
  meanLatency: document.querySelector("#mean-latency"),
  completedJobs: document.querySelector("#completed-jobs"),
  outcomeDetail: document.querySelector("#outcome-detail"),
  jobsList: document.querySelector("#jobs-list"),
  emptyState: document.querySelector("#empty-state"),
  lastUpdated: document.querySelector("#last-updated")
};

let dashboardRequestInFlight = false;
let jobsEtag = null;

async function requestJobs() {
  const headers = jobsEtag ? { "If-None-Match": jobsEtag } : {};
  const response = await fetch("/api/jobs?view=dashboard", { headers, cache: "no-store" });
  if (response.status === 304) return null;
  if (!response.ok) throw new Error(`Jobs request failed: ${response.status}`);
  const body = await response.json();
  jobsEtag = response.headers.get("ETag");
  return body;
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.error || `Request failed with status ${response.status}`);
  }
  return body;
}

async function requestHealth() {
  const response = await fetch("/health");
  return response.json();
}

async function loadDocuments() {
  try {
    const body = await requestJson("/api/documents");
    elements.documentSelect.replaceChildren();
    for (const documentInfo of body.documents) {
      const option = document.createElement("option");
      option.value = documentInfo.id;
      option.textContent = `${documentInfo.id} · ${documentInfo.pageCount} page${documentInfo.pageCount === 1 ? "" : "s"}`;
      elements.documentSelect.append(option);
    }
    const hasDocuments = body.documents.length > 0;
    elements.documentSelect.disabled = !hasDocuments;
    elements.submitButton.disabled = !hasDocuments;
    if (!hasDocuments) {
      showFormMessage("No documents are available.", true);
    }
  } catch (error) {
    elements.documentSelect.replaceChildren();
    const option = document.createElement("option");
    option.textContent = "Documents unavailable";
    elements.documentSelect.append(option);
    showFormMessage(error.message, true);
  }
}

async function refreshDashboard() {
  if (dashboardRequestInFlight) {
    return;
  }
  dashboardRequestInFlight = true;
  elements.jobsList.setAttribute("aria-busy", "true");
  try {
    const [health, metrics, jobs] = await Promise.allSettled([
      requestHealth(),
      requestJson("/api/metrics"),
      requestJobs()
    ]);
    renderHealth(health.status === "fulfilled" ? health.value : null);
    if (metrics.status === "fulfilled") renderMetrics(metrics.value);
    if (jobs.status === "fulfilled" && jobs.value) renderJobs(jobs.value.jobs);
    const failure = [health, metrics, jobs].find(result => result.status === "rejected");
    if (failure) throw failure.reason;
    elements.lastUpdated.textContent = `Updated ${new Date().toLocaleTimeString()}`;
  } catch (error) {
    renderHealth(null);
    elements.lastUpdated.textContent = error.message;
  } finally {
    elements.jobsList.setAttribute("aria-busy", "false");
    dashboardRequestInFlight = false;
  }
}

function renderHealth(health) {
  const isHealthy = health?.status === "ok";
  elements.healthPill.classList.remove("checking", "ok", "degraded");
  elements.healthPill.classList.add(isHealthy ? "ok" : "degraded");
  elements.healthText.textContent = isHealthy ? "OCR ready" : "OCR unavailable";
}

function renderMetrics(metrics) {
  const policyName = metrics.policy === "ESTIMATED_SJF" ? "Estimated SJF" : metrics.policy;
  elements.policyBadge.textContent = `Policy · ${policyName}`;
  elements.activeWorkers.textContent = `${metrics.activeWorkers}/${metrics.workerCount}`;
  elements.workerDetail.textContent = metrics.activeWorkers === 1 ? "1 worker processing" : `${metrics.activeWorkers} workers processing`;
  elements.pendingJobs.textContent = String(metrics.pendingJobs);
  elements.meanLatency.textContent = formatDuration(metrics.meanCompletionLatencyMs);
  elements.completedJobs.textContent = String(metrics.completedJobs);
  elements.outcomeDetail.textContent = `${metrics.failedJobs} failed · ${metrics.cancelledJobs} cancelled`;
}

function renderJobs(jobs) {
  elements.jobsList.replaceChildren();
  elements.emptyState.hidden = jobs.length !== 0;
  const newestFirst = [...jobs].reverse();
  for (const job of newestFirst) {
    elements.jobsList.append(createJobCard(job));
  }
}

function createJobCard(job) {
  const statusName = job.status.toLowerCase();
  const card = document.createElement("article");
  card.className = `job-card ${statusName}`;
  card.setAttribute("aria-label", `${job.documentId} job ${job.status.toLowerCase()}`);

  const identity = document.createElement("div");
  identity.className = "job-identity";
  const name = document.createElement("strong");
  name.textContent = job.documentId;
  const details = document.createElement("small");
  details.textContent = `${job.pageCount} page${job.pageCount === 1 ? "" : "s"} · ${job.id.slice(0, 8)}`;
  identity.append(name, details);

  const status = document.createElement("span");
  status.className = `status-badge ${statusName}`;
  status.textContent = job.status;

  const timings = document.createElement("dl");
  timings.className = "job-timings";
  timings.append(
    timing("Wait", job.queueWaitMs),
    timing("Run", job.runTimeMs),
    timing("Total", job.completionLatencyMs)
  );

  card.append(identity, status, timings);

  if (job.status === "QUEUED" || job.status === "RUNNING") {
    const cancel = document.createElement("button");
    cancel.className = "cancel-button";
    cancel.type = "button";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", () => cancelJob(job.id, cancel));
    card.append(cancel);
  } else {
    const finished = document.createElement("small");
    finished.textContent = job.finishedAt ? formatTimestamp(job.finishedAt) : "Finished";
    card.append(finished);
  }

  const previewText = job.error || job.textPreview;
  if (previewText) {
    const preview = document.createElement("div");
    preview.className = `job-preview${job.error ? " error" : ""}`;
    const label = document.createElement("p");
    label.textContent = job.error ? "OCR error" : "Extracted text preview";
    const content = document.createElement("pre");
    content.textContent = previewText;
    preview.append(label, content);
    card.append(preview);
  }
  return card;
}

function timing(labelText, milliseconds) {
  const group = document.createElement("div");
  const label = document.createElement("dt");
  label.textContent = labelText;
  const value = document.createElement("dd");
  value.textContent = formatDuration(milliseconds);
  group.append(label, value);
  return group;
}

function formatDuration(milliseconds) {
  if (milliseconds === null || milliseconds === undefined) {
    return "—";
  }
  if (milliseconds >= 1000) {
    return `${(milliseconds / 1000).toFixed(2)} s`;
  }
  return `${Math.round(milliseconds)} ms`;
}

function formatTimestamp(timestamp) {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(new Date(timestamp));
}

async function cancelJob(jobId, button) {
  button.disabled = true;
  try {
    await requestJson(`/api/jobs/${jobId}`, { method: "DELETE" });
    showFormMessage("Cancellation requested.");
    await refreshDashboard();
  } catch (error) {
    showFormMessage(error.message, true);
    button.disabled = false;
  }
}

function showFormMessage(message, isError = false) {
  elements.formMessage.textContent = message;
  elements.formMessage.classList.toggle("error", isError);
}

elements.form.addEventListener("submit", async (event) => {
  event.preventDefault();
  elements.submitButton.disabled = true;
  showFormMessage("Adding document to the queue…");
  try {
    const job = await requestJson("/api/jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        documentId: elements.documentSelect.value,
        priority: Number.parseInt(elements.priorityInput.value, 10) || 0
      })
    });
    showFormMessage(`Queued ${job.documentId} job ${job.id.slice(0, 8)}.`);
    await refreshDashboard();
  } catch (error) {
    showFormMessage(error.message, true);
  } finally {
    elements.submitButton.disabled = false;
  }
});

async function poll() {
  await refreshDashboard();
  window.setTimeout(poll, POLL_DELAY_MS);
}

loadDocuments();
poll();
