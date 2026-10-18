#!/usr/bin/env bash
set -euo pipefail

image_name="${1:-ocr-laundry:smoke}"
container_name="ocr-laundry-smoke-${GITHUB_RUN_ID:-local}-$$"
container_runtime="${CONTAINER_RUNTIME:-docker}"

cleanup() {
  "$container_runtime" rm --force "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$container_runtime" build --tag "$image_name" .
"$container_runtime" run --detach \
  --name "$container_name" \
  --cpus 1 \
  --memory 768m \
  --pids-limit 128 \
  --read-only \
  --tmpfs /app/tmp:rw,noexec,nosuid,size=256m,mode=1777 \
  --security-opt no-new-privileges \
  --publish 127.0.0.1::8080 \
  "$image_name" >/dev/null

published_port="$("$container_runtime" port "$container_name" 8080/tcp | sed 's/.*://')"
base_url="http://127.0.0.1:${published_port}"

for attempt in $(seq 1 45); do
  if curl --fail --silent "$base_url/health" >/dev/null; then
    break
  fi
  if [[ "$attempt" == 45 ]]; then
    "$container_runtime" logs "$container_name"
    echo "Container did not become healthy" >&2
    exit 1
  fi
  sleep 1
done

submission="$(curl --fail --silent \
  --header 'Content-Type: application/json' \
  --data '{"documentId":"small","priority":0}' \
  "$base_url/api/jobs")"
job_id="$(printf '%s' "$submission" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
if [[ -z "$job_id" ]]; then
  echo "Submission response did not contain a job ID: $submission" >&2
  exit 1
fi

for attempt in $(seq 1 60); do
  job="$(curl --fail --silent "$base_url/api/jobs/$job_id")"
  if [[ "$job" == *'"status":"COMPLETED"'* ]]; then
    if [[ "$job" != *'OCR LAUNDRY'* ]]; then
      echo "OCR completed without the expected text: $job" >&2
      exit 1
    fi
    echo "Container smoke test passed under 1 CPU, 768 MiB memory and a read-only root filesystem."
    exit 0
  fi
  if [[ "$job" == *'"status":"FAILED"'* || "$job" == *'"status":"CANCELLED"'* ]]; then
    echo "OCR job did not complete: $job" >&2
    exit 1
  fi
  sleep 1
done

"$container_runtime" logs "$container_name"
echo "OCR job did not finish within 60 seconds" >&2
exit 1
