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
