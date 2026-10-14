# Runbook

## Health Check

```bash
curl http://localhost:8080/actuator/health
```

Expected result: `UP`.

## Verify Stored Responses

```bash
curl http://localhost:8080/api/responses
curl http://localhost:8080/api/responses/openTest
```

## Catalog Metrics

Inspect one metric through Actuator or scrape all metrics in Prometheus format:

```bash
curl http://localhost:8080/actuator/metrics/payloadprobe.response.catalog.size
curl http://localhost:8080/actuator/prometheus
```

| Metric | Type | Meaning |
| --- | --- | --- |
| `payloadprobe.response.catalog.size` | Gauge | Current number of stored response keys |
| `payloadprobe.response.reads` | Counter | Completed response lookups, including hits and misses |
| `payloadprobe.response.misses` | Counter | Response lookups that found no stored payload |
| `payloadprobe.response.writes` | Counter | Completed create, update, delete, and response-set import attempts |

Write counters use only fixed `operation=create|update|delete|import` and
`outcome=success|rejected` tags. No response key, XML content, file path, or
request value is recorded. Prometheus exports counters with a `_total` suffix.

Keep `/actuator/prometheus` behind the deployment's existing network access
boundary; it is an operational endpoint, not a public response API.

Import and interpretation guidance for the checked-in dashboard is in the
[Grafana Dashboard](grafana-dashboard.md) page.

## Response-set export and import

Export the current response catalog before moving it to another trusted
environment:

```bash
curl http://localhost:8080/api/response-sets
```

Import a response set with JSON. Check the `201` result and `imported` count:

```bash
curl -X POST http://localhost:8080/api/response-sets \
  -H "Content-Type: application/json" \
  -d '{"version":1,"responses":[{"key":"newResponse","xml":"<response/>"}]}'
```

The import is additive and atomic. A `409` means one or more submitted keys
already exist; no entries were imported. A `400` means the complete JSON
document, including every XML entry, must be corrected before retrying. The
default ceilings are 100 entries, 1,048,576 raw JSON bytes, and 262,144 UTF-8
bytes per XML value. The import endpoint is mutable and unauthenticated: do
not expose it outside trusted external access controls.

PayloadProbe checks atomic-move support in the configured store directory at
startup. If startup reports that atomic moves are unavailable, move
`PAYLOADPROBE_STORE_PATH` to a local filesystem that supports atomic rename;
imports are not enabled with a weaker non-atomic fallback.

## Named Scenarios

Create a reusable, ordered test sequence using keys that already exist in the
catalog:

```bash
curl -X PUT http://localhost:8080/api/scenarios/payment-happy-path \
  -H "Content-Type: application/json" \
  -d '{"responseKeys":["openTest","demo"]}'
```

Confirm that the catalog view contains only scenario metadata, then resolve the
selection when the test runner needs the current XML:

```bash
curl http://localhost:8080/api/scenarios
curl http://localhost:8080/api/scenarios/payment-happy-path
```

The resolved document is accepted by the response-set import endpoint when the
target catalog does not already contain its keys:

```bash
curl http://localhost:8080/api/scenarios/payment-happy-path \
  | curl -X POST http://localhost:8080/api/response-sets \
      -H "Content-Type: application/json" \
      --data-binary @-
```

Sending it back to the same catalog normally returns `409` because response-set
imports are additive and the referenced keys already exist.

The scenario update is atomic: an invalid, duplicate, missing, or stale
response key returns a fixed error and does not create or replace the scenario.
A `409` during a write means a referenced response is unavailable or the
scenario catalog is full; correct the catalog or remove an unused scenario and
retry. A `409` during resolution means a previously referenced response was
removed, or the resolved XML exceeds the response-set export limit. Recreate or
update the scenario for a missing key; reduce its selection or XML size for the
fixed export-limit error. Do not attempt to infer keys or XML from error bodies.

PUT validates references from one response-catalog snapshot before writing the
separate scenario file. A concurrent response deletion can make a newly saved
scenario stale; GET always rechecks the complete selection and returns no
partial XML.

Scenario data is stored separately at `data/scenarios.json`. Default ceilings
are 100 scenarios, 100 response keys per scenario, and 65,536 raw JSON bytes
per write. Set `PAYLOADPROBE_SCENARIOS_PATH`,
`PAYLOADPROBE_SCENARIO_MAX_SCENARIOS`,
`PAYLOADPROBE_SCENARIO_MAX_RESPONSE_KEYS`, or
`PAYLOADPROBE_SCENARIO_MAX_DOCUMENT_BYTES` before startup to change those
limits. The scenario store also requires atomic rename support in its parent
directory. All mutable scenario routes are unauthenticated and must remain
behind trusted external access controls.

## Request Completion Logs

Console logs use Spring Boot's Logstash JSON format. Each completed synchronous
request emits one `request.completed` event with only bounded,
server-controlled fields; async and error redispatches are skipped:

| Field | Meaning |
| --- | --- |
| `event` | Fixed `http_request_completed` event name |
| `method` | `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, or `OTHER` |
| `route` | Allow-listed route template such as `/api/responses/{key}`, or `other` |
| `status` | Final HTTP status, or fixed `500` for an unhandled failure |
| `outcome` | `success`, `client_error`, `server_error`, or `other` |
| `duration_ms` | Server-measured elapsed milliseconds |

The event never reads or records an actual URI or response key, query parameter,
header, cookie, request or response body, XML content, client address, request
identifier, file path, or exception object, type, message, cause, or stack trace.
Unmatched, static, and management routes collapse to the fixed `other` label.

## Common Issues

| Symptom | Likely cause | Action |
| --- | --- | --- |
| `404` for a key | The response was not created or was deleted | Check `/api/responses` and recreate it |
| Store changes disappear | Container has no persistent volume | Mount `./data:/data` or set `PAYLOADPROBE_STORE_PATH` |
| App fails on startup | Store path parent cannot be created | Use a writable path for `PAYLOADPROBE_STORE_PATH` |
| App reports atomic moves are unavailable | Store filesystem cannot guarantee all-or-nothing imports | Move `PAYLOADPROBE_STORE_PATH` to a filesystem with atomic rename support |
| Scenario write returns `409` | A key is missing/stale or the scenario catalog is full | Check the response catalog and scenario count, then retry with valid capacity |
| Scenario resolve returns `409` | A response is missing or the resolved bundle exceeds response-set export limits | Recreate/update missing responses, or reduce the scenario selection/XML size |
| App fails at scenario-store startup | Scenario directory does not support atomic rename | Set `PAYLOADPROBE_SCENARIOS_PATH` on a filesystem with atomic rename support |
| Docs build fails | Missing Python dependencies | Run `pip install -r docs/requirements.txt` |

## Release Checks

- `mvn -T 1C test`
- `mkdocs build --strict`
- GitHub Actions Java CI passes
- GitHub Pages deployment completes after merge to `main`
