# PayloadProbe Lab

PayloadProbe Lab is a Spring Boot service for managing named XML mock responses.
It is designed for teams that need predictable payloads while testing middleware,
integration adapters, or downstream API behavior.

## Capabilities

| Capability | What it demonstrates |
| --- | --- |
| Response catalog | Store XML payloads by key and retrieve them over HTTP |
| Legacy compatibility | Keep original PayloadProbe routes working during modernization |
| Operational readiness | Health checks, Docker, Kubernetes starter manifests, and Prometheus scrape config |
| Portfolio delivery | CI, OpenAPI, live docs, roadmap, runbook, and daily improvement automation |

## First Request

```bash
curl http://localhost:8080/api/responses/openTest
```

## Documentation Map

- [Architecture](architecture.md) explains the runtime flow and deployment view.
- [API Reference](api-reference.md) lists modern and legacy endpoints.
- [Setup Guide](setup-guide.md) covers local, Docker, and Kubernetes startup.
- [Runbook](runbook.md) captures operational checks and recovery steps.
- [Roadmap](roadmap.md) tracks planned improvements.
