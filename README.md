# PayloadProbe Lab

PayloadProbe Lab is a portfolio-grade Spring Boot service for storing, fetching,
and evolving named XML mock responses. It modernizes the original PayloadProbe
idea into a Java 17 service with API tests, Docker, Kubernetes starter manifests,
observability hooks, CI, and live documentation.

Live docs: https://j4vedskh.github.io/payloadprobe-lab/

## What It Shows

| Area | Implementation |
| --- | --- |
| Backend | Java 17, Spring Boot 3.5.16, REST APIs, validation, Actuator |
| Storage | JSON-backed response catalog with bounded, retained response history |
| Compatibility | Legacy aliases for the original PayloadProbe endpoints |
| Platform | Dockerfile, Docker Compose, Kubernetes starter manifests |
| Delivery | GitHub Actions CI and GitHub Pages documentation |

## Quick Start

Build and test with JDK 17:

```bash
mvn -T 1C test
```

Spring Boot is pinned to `3.5.16`, the final open-source release in the 3.5.x
line. This keeps the Java 17 baseline while incorporating the line's latest
fixes. A Spring Boot 4.x migration remains a separate major-version
compatibility task. See the official
[3.5.16 release announcement](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/).

Run locally:

```bash
mvn spring-boot:run
```

Fetch a seeded XML response:

```bash
curl http://localhost:8080/api/responses/openTest
```

Add a new response:

```bash
curl -X POST http://localhost:8080/api/responses/demo \
  -H "Content-Type: application/xml" \
  -d "<response><status>success</status></response>"
```

Each newly created response starts at revision `1`. Read a response's retained
revision metadata (newest first) or retrieve the exact XML from one revision:

```bash
curl http://localhost:8080/api/responses/demo/versions
curl http://localhost:8080/api/responses/demo/versions/1
```

Every successful `PUT /api/responses/{key}` and legacy `/update/{key}` creates
the next revision, even when the XML is unchanged. Imports create revision `1`
for every new key. PayloadProbe retains the latest 10 revisions per response,
including the current revision. Set
`PAYLOADPROBE_RESPONSE_HISTORY_MAX_REVISIONS_PER_RESPONSE` to a value from `1`
to `1000` before startup to change that limit.

Revision numbers only move forward while a response exists; pruned revisions
are not available. Deleting a response removes all of its history, and
recreating the key starts again at revision `1`. Historical reads are
read-only: to make an older XML value current, fetch it and submit it with the
normal `PUT` route, which creates a new revision.

Export the current catalog as a portable response set:

```bash
curl http://localhost:8080/api/response-sets
```

Import a response set. Imports only add new keys: if any key already exists,
the whole request is rejected and the catalog is left unchanged.

```bash
curl -X POST http://localhost:8080/api/response-sets \
  -H "Content-Type: application/json" \
  -d '{"version":1,"responses":[{"key":"demoTwo","xml":"<response><status>success</status></response>"}]}'
```

Response-set defaults are 100 entries and 1,048,576 raw JSON bytes. Change
them with `PAYLOADPROBE_RESPONSE_SET_MAX_ENTRIES` and
`PAYLOADPROBE_RESPONSE_SET_MAX_DOCUMENT_BYTES`. Each XML value remains limited
to 262,144 UTF-8 bytes. `POST /api/response-sets` is one of the optional
protected write routes; see [Write authentication](#write-authentication).

Save an ordered, reusable selection of existing response keys as a named
scenario. Creating a new scenario returns `201`; writing the same name again
replaces its selection and returns `200`.

```bash
curl -X PUT http://localhost:8080/api/scenarios/payment-happy-path \
  -H "Content-Type: application/json" \
  -d '{"responseKeys":["openTest","demo"]}'
```

List scenario metadata without XML content:

```bash
curl http://localhost:8080/api/scenarios
```

Resolve a scenario when a test harness needs its current XML. The response is
the same version-1 response-set document accepted by `POST /api/response-sets`:

```bash
curl http://localhost:8080/api/scenarios/payment-happy-path
```

Scenario names and response keys use the same 1-120-character safe-key rule.
Each scenario must contain an ordered, nonempty, duplicate-free list of keys
that already exist in the response catalog. Scenario storage is separate at
`data/scenarios.json`, validates the entire request before writing, and uses an
atomic file replacement. A stale or missing response reference returns `409`
without returning XML or partially changing a scenario. A resolved scenario
that exceeds the response-set export limit returns a separate fixed `409`.
Reference validation uses one catalog snapshot; a concurrent or later response
deletion can therefore make a saved scenario stale, and resolution always
rechecks before returning XML.

The defaults are 100 scenarios, 100 response keys per scenario, and 65,536 raw
JSON bytes per scenario write. Change them with `PAYLOADPROBE_SCENARIOS_PATH`,
`PAYLOADPROBE_SCENARIO_MAX_SCENARIOS`,
`PAYLOADPROBE_SCENARIO_MAX_RESPONSE_KEYS`, and
`PAYLOADPROBE_SCENARIO_MAX_DOCUMENT_BYTES`. Scenario writes and deletes are
optional protected write routes; see [Write authentication](#write-authentication).

Override the writable store location:

```bash
set PAYLOADPROBE_STORE_PATH=C:\temp\payloadprobe\xmlResponses.json
mvn spring-boot:run
```

On first startup after an upgrade, a legacy flat response map is converted to
the current versioned single-file format through an atomic replacement. Keep a
backup of the store before upgrading, and ensure the store directory supports
atomic rename.

## Write authentication

Write authentication is disabled by default, so local development works
without a token. To protect writes, set **both** variables before startup:

```bash
set PAYLOADPROBE_WRITE_AUTH_ENABLED=true
set PAYLOADPROBE_WRITE_AUTH_BEARER_TOKEN=replace-with-a-16-to-256-character-token
mvn spring-boot:run
```

The token must be 16 to 256 characters using only letters, numbers, `.`, `_`,
`~`, `+`, `/`, `=`, or `-`. Send exactly one matching header on protected
requests:

```bash
curl -X POST http://localhost:8080/api/responses/demo \
  -H "Authorization: Bearer replace-with-a-16-to-256-character-token" \
  -H "Content-Type: application/xml" \
  -d "<response><status>success</status></response>"
```

When enabled, exactly these nine write routes require that header: modern
response `POST`, `PUT`, and `DELETE`; response-set `POST`; scenario `PUT` and
`DELETE`; and legacy `/add`, `/update`, and `/delete`. All `GET` routes, legacy
`POST /fetch/{key}`, `/default`, and `POST /api/responses/default` remain
public.

A missing, malformed, duplicate, or incorrect authorization header returns
`401` with `{"message":"Write authentication required."}`, a
`WWW-Authenticate` header, and `Cache-Control: no-store`. Tokens are never
written to request logs. This shared token is not per-user authentication or a
role model: keep the service behind TLS and appropriate network access
controls.

Every attempt on one of the nine write routes also produces one structured
console audit event. It records only the route template, HTTP method,
authentication result, final status, and broad outcome. It never includes a
response key, scenario name, token, payload, query, header, cookie, client
identity, IP address, request ID, file path, exception, or duration. Public
and read routes do not produce audit events. This is best-effort operational
logging, not a durable, tamper-proof, or compliance audit ledger; the external
log collector controls retention and access.

## Documentation

Install and build the documentation site:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

The docs use Material for MkDocs, Mermaid diagrams, and a versioned OpenAPI
contract.
