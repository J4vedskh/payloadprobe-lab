# API Reference

The canonical API is under `/api/responses`. Legacy aliases are preserved so
older PayloadProbe clients have a migration path.

## Modern API

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/responses` | JSON catalog with `count` and `keys` |
| `GET` | `/api/responses/{key}` | XML response for the key |
| `POST` | `/api/responses/{key}` | Create XML response, `409` if it exists |
| `PUT` | `/api/responses/{key}` | Update XML response, `404` if missing |
| `DELETE` | `/api/responses/{key}` | Delete XML response, `404` if missing |
| `POST` | `/api/responses/default` | Default XML sample |
| `GET` | `/actuator/health` | Spring Boot health status |

Keys are limited to 1-120 letters, numbers, dots, underscores, or hyphens. Invalid keys return `400` with a JSON error body.

## Legacy Aliases

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/fetch/{key}` | Fetch XML by key |
| `POST` | `/fetch/{key}` | Fetch XML by key while accepting legacy POST clients |
| `POST` | `/add/{key}` | Create XML response |
| `POST` | `/update/{key}` | Update XML response |
| `DELETE` | `/delete/{key}` | Delete XML response |
| `GET` | `/fetchAll` | List response keys |
| `GET` | `/default` | Default XML sample |
| `POST` | `/default` | Default XML sample |
| `GET` | `/help` | Endpoint catalog |

Legacy aliases use the same key validation rules as the modern API.

## OpenAPI

The versioned OpenAPI contract lives at [`docs/api/openapi.yaml`](api/openapi.yaml)
and is published with the documentation site.
