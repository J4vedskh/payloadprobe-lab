# Roadmap

## Completed

- Validate modern and legacy write payloads with stable JSON error responses.
- Cover every legacy alias with controller-level contract tests.
- Add bounded Micrometer metrics for catalog size, reads, writes, and misses.
- Add privacy-safe structured request completion logging.
- Add an importable Grafana dashboard starter for bounded operational metrics.
- Add versioned response-set export and additive, atomic import.
- Add named scenarios with ordered, validated response selections and portable resolution.
- Add bounded response version history with retained XML reads.
- Add optional Bearer authentication for exactly the nine write endpoints.
- Add privacy-bounded structured audit events for protected write attempts.

## Near Term

- Review and update direct and transitive dependencies for security fixes.

## Platform

- Publish the generated OpenAPI contract through the documentation workflow.
- Publish Docker images through GitHub Actions.
- Add Helm chart or Kustomize overlays.
- Add readiness and liveness probes in Kubernetes.
- Add container image vulnerability scanning.

## Observability

- Define environment-qualified alert rules after a real traffic baseline exists.

## Product Depth

- Evaluate per-user roles only if a deployment needs identities beyond the shared write token.
