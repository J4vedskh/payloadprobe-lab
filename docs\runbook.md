# Runbook

## Health Check

```bash
curl http://localhost:8080/actuator/health
```

Expected result: `UP`.

## Verify Stored Responses

```bash
curl http://localhost:8080/api/responses
curl http://localhost:8080/api/responses/openTest
```

## Common Issues

| Symptom | Likely cause | Action |
| --- | --- | --- |
| `404` for a key | The response was not created or was deleted | Check `/api/responses` and recreate it |
| Store changes disappear | Container has no persistent volume | Mount `./data:/data` or set `PAYLOADPROBE_STORE_PATH` |
| App fails on startup | Store path parent cannot be created | Use a writable path for `PAYLOADPROBE_STORE_PATH` |
| Docs build fails | Missing Python dependencies | Run `pip install -r docs/requirements.txt` |

## Release Checks

- `mvn -T 1C test`
- `mkdocs build --strict`
- GitHub Actions Java CI passes
- GitHub Pages deployment completes after merge to `main`
