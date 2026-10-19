# API Reference

The canonical API is under `/api/responses`. Legacy aliases are preserved so
older PayloadProbe clients have a migration path.

Response sets are available at `/api/response-sets`. They provide a portable,
versioned JSON export and an additive-only import for a catalog.

Named scenarios are available at `/api/scenarios`. They store an ordered
selection of response keys for a repeatable test case without copying XML.

## Modern API

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/responses` | JSON catalog with `count` and `keys` |
| `GET` | `/api/responses/{key}` | XML response for the key |
| `POST` | `/api/responses/{key}` | Create XML response, `409` if it exists |
| `PUT` | `/api/responses/{key}` | Update XML response, `404` if missing |
| `DELETE` | `/api/responses/{key}` | Delete XML response, `404` if missing |
| `GET` | `/api/responses/{key}/versions` | Retained revision metadata, newest first |
| `GET` | `/api/responses/{key}/versions/{revision}` | Exact XML for one retained revision |
| `POST` | `/api/responses/default` | Default XML sample |
| `GET` | `/api/response-sets` | Export the complete response catalog |
| `POST` | `/api/response-sets` | Add a response set without overwriting keys |
| `GET` | `/api/scenarios` | Metadata-only scenario list, sorted by name |
| `PUT` | `/api/scenarios/{name}` | Create or replace an ordered response-key selection |
| `GET` | `/api/scenarios/{name}` | Resolve current XML as a version-1 response set |
| `DELETE` | `/api/scenarios/{name}` | Delete a scenario |
| `GET` | `/actuator/health` | Spring Boot health status |
| `GET` | `/actuator/metrics/{metricName}` | One Actuator metric and its available tags |
| `GET` | `/actuator/prometheus` | Prometheus-format operational metrics |

Keys are limited to 1-120 letters, numbers, dots, underscores, or hyphens. Invalid keys return `400` with a JSON error body.

## Write Authentication

Write authentication is disabled by default. Enable it only by setting both
`PAYLOADPROBE_WRITE_AUTH_ENABLED=true` and
`PAYLOADPROBE_WRITE_AUTH_BEARER_TOKEN` before startup. The token must be 16 to
256 characters from `A-Z`, `a-z`, `0-9`, `.`, `_`, `~`, `+`, `/`, `=`, and `-`.

When enabled, exactly nine mutating routes require exactly one matching
`Authorization: Bearer <token>` header:

| API family | Protected routes |
| --- | --- |
| Modern responses | `POST`, `PUT`, and `DELETE /api/responses/{key}` |
| Response sets | `POST /api/response-sets` |
| Scenarios | `PUT` and `DELETE /api/scenarios/{name}` |
| Legacy aliases | `POST /add/{key}`, `POST /update/{key}`, and `DELETE /delete/{key}` |

All `GET` routes remain public. `POST /fetch/{key}`, `GET|POST /default`, and
`POST /api/responses/default` also remain public.

A missing, malformed, duplicated, or incorrect header returns `401`:

```json
{
  "message": "Write authentication required."
}
```

The response also includes `WWW-Authenticate` and `Cache-Control: no-store`.
The shared token is not a per-user identity or role model. Use TLS and network
access controls in every deployment; tokens are not logged.

Every attempt on one of the nine write routes produces one structured console audit
event. The event uses `write.audit.completed` with the fixed event value
`protected_write_completed`, and includes only `method`, a route template,
`authentication`, `status`, and `outcome`. Authentication is one of
`not_required`, `accepted`, `rejected`, or `undetermined`; outcome is one of
`success`, `client_error`, `server_error`, or `other`. The event never contains
keys, names, tokens, bodies, queries, headers, cookies, IP addresses, request
IDs, file paths, exceptions, durations, or user identities. Public and read
routes do not produce it. This is best-effort logging rather than a durable,
tamper-proof, or compliance audit ledger; retention and access belong to the
external log collector.

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

## Response History

Every response has an append-only, bounded history. A successful `POST` or
response-set import creates revision `1`. Every successful modern `PUT` and
legacy `/update/{key}` appends the next revision, including a write whose XML
matches the current value. Existing catalog, response-set, and scenario reads
continue to use only the current XML.

`GET /api/responses/{key}/versions` returns retained metadata only, newest
first:

```json
{
  "key": "demo",
  "currentRevision": 3,
  "retainedCount": 3,
  "versions": [
    { "revision": 3, "current": true },
    { "revision": 2, "current": false },
    { "revision": 1, "current": false }
  ]
}
```

`GET /api/responses/{key}/versions/{revision}` returns that retained revision's
exact XML with an XML content type. Revision identifiers remain monotonic while
the response exists, so the retained list can have gaps after pruning. The
default retention limit is 10 revisions per response, including the current
revision. Configure a value from 1 through 1000 with
`PAYLOADPROBE_RESPONSE_HISTORY_MAX_REVISIONS_PER_RESPONSE` before startup.

There is deliberately no rollback endpoint. To promote an older retained XML,
fetch it and send it to `PUT /api/responses/{key}`; that preserves revision
history by appending a new revision. Deleting a response purges its full history;
if the key is later recreated, its history begins again at revision `1`.

A missing response returns the normal response-key `404`. A missing or pruned
revision returns `404`, and a revision that is not a positive whole number
returns `400`. History endpoints are read-only. When optional write
authentication is enabled, the mutating routes listed above require the Bearer
header; otherwise they retain the local-development default of no auth.

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
`PAYLOADPROBE_RESPONSE_SET_MAX_DOCUMENT_BYTES`. When optional write
authentication is enabled, imports require the Bearer header described above.

## Named Scenarios

Named scenarios make a test selection reusable while keeping the response
catalog as the single XML source of truth. A scenario stores only an ordered
list of existing response keys; it does not duplicate XML content.

`GET /api/scenarios` returns metadata only, ordered by scenario name. It never
returns response keys or XML values:

```json
{
  "count": 1,
  "scenarios": [
    {
      "name": "payment-happy-path",
      "responseCount": 2
    }
  ]
}
```

Create or replace a scenario with `PUT /api/scenarios/{name}` and
`Content-Type: application/json`. The list must be nonempty, ordered, contain
no duplicate entries, and every key must currently exist. A first write returns
`201`; a replacement returns `200`:

```json
{
  "responseKeys": ["openTest", "demo"]
}
```

`GET /api/scenarios/{name}` resolves those keys against the current response
catalog and returns the exact version-1 `ResponseSetDocument` shape shown in
the Response Sets section. Send that response directly to
`POST /api/response-sets` when moving the scenario's current XML to another
trusted catalog that does not already contain those response keys. Importing
back into the same catalog returns the response-set importer's normal `409`.

`DELETE /api/scenarios/{name}` returns `204`; an unknown scenario returns
`404`. A named scenario is deliberately not a frozen payload snapshot: if a
stored key is later removed, resolving the scenario returns `409` and no XML
is returned. Resolution also returns a different fixed `409` when the current
XML bundle exceeds the configured response-set export limit.

Scenario writes validate references against one catalog snapshot immediately
before the separate atomic scenario-file write. A concurrent response deletion
can make a newly written scenario stale; every resolution rechecks all keys and
fails without partial XML.

The scenario document, limits, names, and key references are all checked
before storage changes. Failures use a fixed JSON `message` only and never
echo a scenario name, response key, XML value, parser detail, or filesystem
path:

| Operation | Status | Message |
| --- | --- | --- |
| Create or replace | `400` | `Invalid scenario document.` |
| Create or replace | `413` | `Scenario document exceeds the configured size limit.` |
| Create or replace or resolve | `409` | `Scenario references an unavailable response.` |
| Resolve | `409` | `Scenario exceeds the configured response-set export limit.` |
| Create | `409` | `Scenario catalog limit reached.` |
| Resolve or delete | `404` | `No scenario found.` |
| Create or replace | `415` | `Unsupported Content-Type. Use application/json.` |
| Create, replace, or delete | `503` | `Scenario could not be persisted.` |

Scenarios are persisted separately in `data/scenarios.json`, with an atomic
replacement in the same directory. Defaults are 100 scenarios, 100 response
keys per scenario, and 65,536 raw JSON bytes. Configure them with
`PAYLOADPROBE_SCENARIOS_PATH`, `PAYLOADPROBE_SCENARIO_MAX_SCENARIOS`,
`PAYLOADPROBE_SCENARIO_MAX_RESPONSE_KEYS`, and
`PAYLOADPROBE_SCENARIO_MAX_DOCUMENT_BYTES`. When optional write authentication
is enabled, scenario writes and deletes require the Bearer header described
above.

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
JSON `404` instead. A successful legacy `/update/{key}` appends a response
revision exactly like the modern `PUT` route. Only legacy `/add`, `/update`,
and `/delete` are protected when optional write authentication is enabled;
legacy fetch and default routes remain public.

## OpenAPI

The versioned OpenAPI contract lives at [`docs/api/openapi.yaml`](api/openapi.yaml)
and is published with the documentation site.
