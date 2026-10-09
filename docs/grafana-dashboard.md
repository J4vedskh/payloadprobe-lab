# Grafana Dashboard

The importable dashboard at
[`ops/grafana/payloadprobe-overview.json`](https://github.com/J4vedskh/payloadprobe-lab/blob/main/ops/grafana/payloadprobe-overview.json)
shows PayloadProbe's current catalog state, response activity, and HTTP health.
It is a dashboard artifact only: it does not install Grafana or change any
deployment configuration.

## What You See First

The first row answers four immediate questions:

1. Is Prometheus successfully scraping the selected target?
2. How many XML responses are currently stored?
3. How many lookups occurred in the selected time range?
4. Did the application return any `5xx` responses in that range?

The supporting charts show lookup and miss rates, miss percentage, write
outcomes, HTTP outcomes, and average non-Actuator request duration.

## Import

1. Make sure PayloadProbe exposes `/actuator/prometheus` and Prometheus is
   scraping it. The repository example uses a 15-second interval in
   `ops/prometheus/prometheus.yml`.
2. In Grafana, open **Dashboards → New → Import**.
3. Upload `ops/grafana/payloadprobe-overview.json`.
4. Select the Prometheus datasource when Grafana asks for `DS_PROMETHEUS`.
5. Choose the `payloadprobe` job and the instance or instances to inspect.

The dashboard defaults to the last six hours and refreshes every 30 seconds.
Grafana stacks the 24-column panel layout on narrow screens, keeping the status
row before the detailed trends.

## Reading the Data Safely

- **Service target** is conservative: `DOWN` means at least one selected target
  is down. `No data` means no selected target is currently scraped.
- `No data` is not the same as zero. Check the datasource, job, instance, and
  time range before drawing a conclusion.
- The red `5xx` color is a visual cue only; this starter creates no alert rule.
- HTTP charts exclude `/actuator/**` scrape and management traffic by filtering
  the canonical Micrometer `uri` label. They never group or display URI values.
- Miss percentage intentionally remains empty when there are no lookup samples.
- Rate panels, especially write outcomes, can show `No data` during sparse
  traffic even while the selected target is healthy.
- Average duration is a mean in seconds, not a percentile. Histogram buckets
  are not enabled by this starter.
- The dashboard uses `$__rate_interval` for rate charts so Grafana can choose a
  window compatible with the datasource scrape interval.

No query uses response keys, XML payloads, request identifiers, client
addresses, headers, cookies, exception text, or another user-controlled label.
