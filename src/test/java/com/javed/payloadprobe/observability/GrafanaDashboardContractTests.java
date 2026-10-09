package com.javed.payloadprobe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GrafanaDashboardContractTests {

    private static final Path DASHBOARD =
            Path.of("ops", "grafana", "payloadprobe-overview.json");
    private static final String PROMETHEUS_INPUT = "${DS_PROMETHEUS}";
    private static final Pattern METRIC_NAME =
            Pattern.compile("(?<![A-Za-z0-9_:])([A-Za-z_:][A-Za-z0-9_:]*)\\s*\\{");
    private static final Set<String> ALLOWED_METRICS = Set.of(
            "up",
            "payloadprobe_response_catalog_size",
            "payloadprobe_response_reads_total",
            "payloadprobe_response_misses_total",
            "payloadprobe_response_writes_total",
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_sum");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dashboardIsPortableAndPanelsDoNotOverlap() throws IOException {
        JsonNode dashboard = readDashboard();

        assertThat(dashboard.path("title").asText()).isEqualTo("PayloadProbe Operations Overview");
        assertThat(dashboard.path("id").isNull()).isTrue();
        assertThat(dashboard.path("uid").isNull()).isTrue();
        assertThat(dashboard.path("schemaVersion").asInt()).isGreaterThanOrEqualTo(39);
        assertThat(dashboard.path("refresh").asText()).isEqualTo("30s");
        assertThat(dashboard.path("time").path("from").asText()).isEqualTo("now-6h");

        JsonNode inputs = dashboard.path("__inputs");
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).path("name").asText()).isEqualTo("DS_PROMETHEUS");
        assertThat(inputs.get(0).path("pluginId").asText()).isEqualTo("prometheus");

        JsonNode panels = dashboard.path("panels");
        assertThat(panels).hasSize(9);
        Set<Integer> panelIds = new HashSet<>();
        List<PanelRectangle> rectangles = new ArrayList<>();
        for (JsonNode panel : panels) {
            int id = panel.path("id").asInt();
            assertThat(panelIds.add(id)).as("unique panel id %s", id).isTrue();
            assertThat(panel.path("description").asText()).isNotBlank();
            assertThat(panel.path("fieldConfig").path("defaults").path("noValue").asText())
                    .isEqualTo("No data");
            assertThat(panel.has("alert")).isFalse();
            assertDatasource(panel.path("datasource"));
            for (JsonNode target : panel.path("targets")) {
                assertDatasource(target.path("datasource"));
            }

            JsonNode grid = panel.path("gridPos");
            PanelRectangle rectangle = new PanelRectangle(
                    id,
                    grid.path("x").asInt(),
                    grid.path("y").asInt(),
                    grid.path("w").asInt(),
                    grid.path("h").asInt());
            assertThat(rectangle.x()).isBetween(0, 23);
            assertThat(rectangle.width()).isBetween(1, 24);
            assertThat(rectangle.x() + rectangle.width()).isLessThanOrEqualTo(24);
            assertThat(rectangle.height()).isPositive();
            rectangles.add(rectangle);
        }

        for (int first = 0; first < rectangles.size(); first++) {
            for (int second = first + 1; second < rectangles.size(); second++) {
                assertThat(overlaps(rectangles.get(first), rectangles.get(second)))
                        .as("panels %s and %s must not overlap", rectangles.get(first).id(), rectangles.get(second).id())
                        .isFalse();
            }
        }
    }

    @Test
    void queriesUseOnlyVerifiedMetricsAndBoundedLabels() throws IOException {
        JsonNode dashboard = readDashboard();
        List<String> expressions = new ArrayList<>();
        Set<String> metricNames = new HashSet<>();

        for (JsonNode panel : dashboard.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expression = target.path("expr").asText();
                expressions.add(expression);
                assertThat(expression)
                        .contains("job=~\"$job\"")
                        .contains("instance=~\"$instance\"");
                if (expression.contains("rate(")) {
                    assertThat(expression).contains("[$__rate_interval]");
                }
                if (expression.contains("increase(")) {
                    assertThat(expression).contains("[$__range]");
                }
                Matcher matcher = METRIC_NAME.matcher(expression);
                while (matcher.find()) {
                    metricNames.add(matcher.group(1));
                }
            }
        }

        assertThat(metricNames).containsExactlyInAnyOrderElementsOf(ALLOWED_METRICS);
        String allQueries = String.join("\n", expressions);
        assertThat(allQueries)
                .contains("uri!~\"/actuator.*\"")
                .doesNotContain(
                        "response_key",
                        "payload=",
                        "payload=~",
                        "payload_key",
                        "request_id",
                        "client_address",
                        "authorization=",
                        "cookie=",
                        "header=",
                        "by (uri)",
                        "{{uri}}",
                        "http://",
                        "https://");
    }

    @Test
    void dashboardHasTheExpectedScanOrderUnitsAndFilters() throws IOException {
        JsonNode dashboard = readDashboard();
        Map<String, String> expectedUnits = Map.of(
                "Maximum catalog size", "short",
                "Lookups in range", "short",
                "5xx responses in range", "short",
                "Lookup traffic", "reqps",
                "Lookup miss ratio", "percent",
                "Write outcomes", "reqps",
                "HTTP request outcomes", "reqps",
                "Average HTTP duration", "s");

        List<String> titles = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            String title = panel.path("title").asText();
            titles.add(title);
            if (expectedUnits.containsKey(title)) {
                assertThat(panel.path("fieldConfig").path("defaults").path("unit").asText())
                        .isEqualTo(expectedUnits.get(title));
            }
        }
        assertThat(titles).containsExactly(
                "Service target",
                "Maximum catalog size",
                "Lookups in range",
                "5xx responses in range",
                "Lookup traffic",
                "Lookup miss ratio",
                "Write outcomes",
                "HTTP request outcomes",
                "Average HTTP duration");

        List<String> variableNames = StreamSupport.stream(
                        dashboard.path("templating").path("list").spliterator(), false)
                .map(variable -> variable.path("name").asText())
                .toList();
        assertThat(variableNames).containsExactly("job", "instance");
        for (JsonNode variable : dashboard.path("templating").path("list")) {
            assertDatasource(variable.path("datasource"));
            assertThat(variable.path("includeAll").asBoolean()).isTrue();
            assertThat(variable.path("multi").asBoolean()).isTrue();
        }
        assertThat(dashboard.path("templating").path("list").get(0).path("definition").asText())
                .isEqualTo("label_values(payloadprobe_response_catalog_size, job)");
        assertThat(dashboard.path("templating").path("list").get(1).path("definition").asText())
                .isEqualTo("label_values(payloadprobe_response_catalog_size{job=~\"$job\"}, instance)");
    }

    private JsonNode readDashboard() throws IOException {
        return objectMapper.readTree(Files.readString(DASHBOARD));
    }

    private void assertDatasource(JsonNode datasource) {
        assertThat(datasource.path("type").asText()).isEqualTo("prometheus");
        assertThat(datasource.path("uid").asText()).isEqualTo(PROMETHEUS_INPUT);
    }

    private boolean overlaps(PanelRectangle first, PanelRectangle second) {
        return first.x() < second.x() + second.width()
                && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height()
                && first.y() + first.height() > second.y();
    }

    private record PanelRectangle(int id, int x, int y, int width, int height) {
    }
}
