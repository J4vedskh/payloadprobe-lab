# API Reference

The canonical API is under `/api/responses`. Legacy aliases are preserved so
older PayloadProbe clients have a migration path.

Response sets are available at `/api/response-sets`. They provide a portable,
versioned JSON export and an additive-only import for a catalog.

## Modern API

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/responses` | JSON catalog with `count` and `keys` |
| `GET` | `/api/responses/{key}` | XML response for the key |
| `POST` | `/api/responses/{key}` | Create XML response, `409` if it exists |
| `PUT` | `/api/responses/{key}` | Update XML response, `404` if missing |
| `DELETE` | `/api/responses/{key}` | Delete XML response, `404` if missing |
| `POST` | `/api/responses/default` | Default XML sample |
| `GET` | `/api/response-sets` | Export the complete response catalog |
| `POST` | `/api/response-sets` | Add a response set without overwriting keys |
| `GET` | `/actuator/health` | Spring Boot health status |
| `GET` | `/actuator/metrics/{metricName}` | One Actuator metric and its available tags |
| `GET` | `/actuator/prometheus` | Prometheus-format operational metrics |

Keys are limited to 1-120 letters, numbers, dots, underscores, or hyphens. Invalid keys return `400` with a JSON error body.

## XML Request Validation

Create and update requests use `application/xml`, `text/xml`, or `text/plain`.
The body must:

- contain at least one XML element;
- be a well-formed XML document or fragment;
- stay within 262144 UTF-8 bytes by default; and
- not contain a DTD or external entity.

Fragments with more than one top-level element remain supported for compatibility.
PayloadProbe stores accepted body text unchanged. Set
`PAYLOADPROBE_MAX_PAYLOAD_BYTES` to change the application-level storage limit.

Invalid bodies return one stable JSON field and are not written to the store:

```json
{
  "message": "XML response content must not be blank."
}
```

Unsupported request media types return `415` with the same one-field JSON
shape. The full set of fixed error examples is in the OpenAPI contract.

## Response Sets

`GET /api/response-sets` returns the current catalog in a stable, versioned
JSON shape. Entries are ordered by key:

```json
{
  "version": 1,
  "responses": [
    {
      "key": "openTest",
      "xml": "<response><status>success</status></response>"
    }
  ]
}
```

Send the same shape to `POST /api/response-sets` with
`Content-Type: application/json`. A successful import returns `201`:

```json
{
  "imported": 1
}
```

Imports are additive only. Every entry is checked before storage is changed,
including the key, XML, version, document structure, duplicate keys, and
limits. If any submitted key already exists, the service returns `409` and
imports nothing. A successful import writes a replacement file in the same
directory and atomically swaps it into place. The application verifies atomic
move support in the store directory during startup and refuses to start when
that guarantee is unavailable.

The default limits are 100 entries and 1,048,576 bytes for the raw JSON
request. Each XML value keeps the normal 262,144 UTF-8-byte limit. Unknown
JSON fields and duplicate JSON fields are rejected. The response-set errors
use one fixed `message` field:

| Operation | Status | Message |
| --- | --- | --- |
| Import | `400` | `Invalid response-set document.` |
| Import | `413` | `Response-set document exceeds the configured size limit.` |
| Import | `409` | `Response-set import conflicts with existing response keys.` |
| Import | `415` | `Unsupported Content-Type. Use application/json.` |
| Import | `503` | `Response-set import could not be persisted.` |
| Export | `409` | `Current response catalog exceeds the configured response-set export limit.` |

Configure the two response-set limits with
`PAYLOADPROBE_RESPONSE_SET_MAX_ENTRIES` and
`PAYLOADPROBE_RESPONSE_SET_MAX_DOCUMENT_BYTES`. The application has no
authentication, so keep imports and all mutable routes behind trusted external
access controls.

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

Legacy aliases use the same key and body validation rules as the modern API.
For compatibility, a missing legacy `GET|POST /fetch/{key}` still returns
`200 application/xml` with a failure payload. The modern fetch route returns a
JSON `404` instead.

## OpenAPI

The versioned OpenAPI contract lives at [`docs/api/openapi.yaml`](api/openapi.yaml)
and is published with the documentation site.
