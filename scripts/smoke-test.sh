#!/usr/bin/env sh
set -eu

base_url="${BASE_URL:-http://localhost:8080}"

curl -fsS "${base_url}/actuator/health/readiness"
curl -fsS "${base_url}/api/admin/projection/status"
curl -fsS "${base_url}/api/admin/projection/memoptimized"
curl -fsS "${base_url}/api/read/calculations/1?mode=projection"
curl -fsS "${base_url}/api/read/calculations/1?mode=transactional"
curl -fsS "${base_url}/api/admin/projection/plan/1"

echo "Smoke test completed successfully."
