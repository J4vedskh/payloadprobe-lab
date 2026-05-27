# Setup Guide

## Prerequisites

- JDK 17
- Maven 3.9 or newer
- Docker Desktop for container runs
- Python 3.12 for documentation builds

## Local Run

```bash
mvn -T 1C test
mvn spring-boot:run
```

The app listens on port `8080`.

## Data Store

By default, the writable store is `data/xmlResponses.json`. The first startup
seeds it from `src/main/resources/xmlResponses.json`.

Override the location with:

```bash
set PAYLOADPROBE_STORE_PATH=C:\temp\payloadprobe\xmlResponses.json
```

## Docker

```bash
mvn -T 1C package
docker compose up --build
```

## Kubernetes

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

The manifests are starter assets for local clusters and can evolve toward Helm
or Kustomize as the project matures.
