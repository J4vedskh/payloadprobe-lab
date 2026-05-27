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
