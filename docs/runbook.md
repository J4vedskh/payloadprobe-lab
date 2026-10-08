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
| `payloadprobe.response.writes` | Counter | Completed create, update, and delete attempts |

Write counters use only fixed `operation=create|update|delete` and
`outcome=success|rejected` tags. No response key, XML content, file path, or
request value is recorded. Prometheus exports counters with a `_total` suffix.

Keep `/actuator/prometheus` behind the deployment's existing network access
boundary; it is an operational endpoint, not a public response API.

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
| Docs build fails | Missing Python dependencies | Run `pip install -r docs/requirements.txt` |

## Release Checks

- `mvn -T 1C test`
- `mkdocs build --strict`
- GitHub Actions Java CI passes
- GitHub Pages deployment completes after merge to `main`
