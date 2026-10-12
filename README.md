# PayloadProbe Lab

PayloadProbe Lab is a portfolio-grade Spring Boot service for storing, fetching,
and evolving named XML mock responses. It modernizes the original PayloadProbe
idea into a Java 17 service with API tests, Docker, Kubernetes starter manifests,
observability hooks, CI, and live documentation.

Live docs: https://j4vedskh.github.io/payloadprobe-lab/

## What It Shows

| Area | Implementation |
| --- | --- |
| Backend | Java 17, Spring Boot 3, REST APIs, validation, Actuator |
| Storage | JSON-backed response catalog seeded from classpath data |
| Compatibility | Legacy aliases for the original PayloadProbe endpoints |
| Platform | Dockerfile, Docker Compose, Kubernetes starter manifests |
| Delivery | GitHub Actions CI and GitHub Pages documentation |

## Quick Start

Build and test with JDK 17:

```bash
mvn -T 1C test
```

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
to 262,144 UTF-8 bytes. The application currently has no authentication, so
keep all write routes, including `POST /api/response-sets`, behind trusted
external access controls.

Override the writable store location:

```bash
set PAYLOADPROBE_STORE_PATH=C:\temp\payloadprobe\xmlResponses.json
mvn spring-boot:run
```

## Documentation

Install and build the documentation site:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

The docs use Material for MkDocs, Mermaid diagrams, and a versioned OpenAPI
contract.
