package com.javed.payloadprobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.observability.RequestCompletionLoggingFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "payloadprobe.store.path=target/test-data/xmlResponses-${random.uuid}.json",
            "management.health.diskspace.enabled=false"
        })
@AutoConfigureObservability
@AutoConfigureMockMvc
class PayloadProbeApplicationTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void fetchesSeededXmlResponse() throws Exception {
        mockMvc.perform(get("/api/responses/openTest"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<Open>15</Open>")));
    }

    @Test
    void createsListsUpdatesAndDeletesXmlResponse() throws Exception {
        String key = "demo-" + UUID.randomUUID();
        String initialXml = "<response><status>created</status></response>";
        String updatedXml = "<response><status>updated</status></response>";

        mockMvc.perform(post("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(initialXml))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(initialXml))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/responses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys").value(hasItem(key)));

        mockMvc.perform(put("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(updatedXml))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/responses/{key}", key))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("updated")));

        mockMvc.perform(delete("/api/responses/{key}", key))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/responses/{key}", key))
                .andExpect(status().isNotFound());
    }

    @Test
    void exposesDefaultXmlAndHealth() throws Exception {
        mockMvc.perform(post("/api/responses/default"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Default XML response")));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UP")));
    }

    @Test
    void exposesCatalogMetricsToActuatorAndPrometheus() throws Exception {
        String key = "metrics-" + UUID.randomUUID();

        mockMvc.perform(get("/api/responses/openTest"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/responses/missing-metrics-key"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<metrics/>"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<metrics/>"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/actuator/metrics/{metricName}", "payloadprobe.response.catalog.size"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("payloadprobe.response.catalog.size"))
                .andExpect(jsonPath("$.measurements[0].value").isNumber());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("payloadprobe_response_catalog_size")))
                .andExpect(content().string(containsString("payloadprobe_response_reads_total")))
                .andExpect(content().string(containsString("payloadprobe_response_misses_total")))
                .andExpect(content().string(containsString("payloadprobe_response_writes_total")))
                .andExpect(content().string(containsString("http_server_requests_seconds_count")))
                .andExpect(content().string(containsString("http_server_requests_seconds_sum")))
                .andExpect(content().string(containsString("outcome=\"SUCCESS\"")))
                .andExpect(content().string(containsString("uri=\"/api/responses/{key}\"")))
                .andExpect(content().string(containsString("operation=\"create\"")))
                .andExpect(content().string(containsString("outcome=\"success\"")))
                .andExpect(content().string(containsString("outcome=\"rejected\"")));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void emitsOneSafeStructuredCompletionEventForMappedRequests(CapturedOutput output) throws Exception {
        String key = "logging-private-key-" + UUID.randomUUID();
        String bodySecret = "logging-body-secret";
        String querySecret = "logging-query-secret";
        String headerSecret = "logging-header-secret";
        Logger requestLogger = (Logger) LoggerFactory.getLogger(RequestCompletionLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        requestLogger.addAppender(appender);

        try {
            mockMvc.perform(post("/api/responses/{key}", key)
                            .queryParam("token", querySecret)
                            .header("Authorization", "Bearer " + headerSecret)
                            .contentType(MediaType.APPLICATION_XML)
                            .content("<response><token>" + bodySecret + "</token></response>"))
                    .andExpect(status().isCreated());
        } finally {
            requestLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("request.completed");
            assertThat(event.getKeyValuePairs())
                    .extracting(pair -> pair.key, pair -> pair.value)
                    .contains(
                            org.assertj.core.groups.Tuple.tuple("event", "http_request_completed"),
                            org.assertj.core.groups.Tuple.tuple("method", "POST"),
                            org.assertj.core.groups.Tuple.tuple("route", "/api/responses/{key}"),
                            org.assertj.core.groups.Tuple.tuple("status", 201),
                            org.assertj.core.groups.Tuple.tuple("outcome", "success"));
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                    .doesNotContain(key, bodySecret, querySecret, headerSecret);
        });

        String renderedJson = output.getOut().lines()
                .filter(line -> line.contains("\"message\":\"request.completed\""))
                .filter(line -> line.contains(RequestCompletionLoggingFilter.class.getName()))
                .reduce((first, second) -> second)
                .orElseThrow();
        JsonNode structuredEvent = new ObjectMapper().readTree(renderedJson);
        assertThat(structuredEvent.get("event").asText()).isEqualTo("http_request_completed");
        assertThat(structuredEvent.get("method").asText()).isEqualTo("POST");
        assertThat(structuredEvent.get("route").asText()).isEqualTo("/api/responses/{key}");
        assertThat(structuredEvent.get("status").asInt()).isEqualTo(201);
        assertThat(structuredEvent.get("outcome").asText()).isEqualTo("success");
        assertThat(structuredEvent.get("duration_ms").isIntegralNumber()).isTrue();
        assertThat(structuredEvent.toString()).doesNotContain(key, bodySecret, querySecret, headerSecret);
    }

    @Test
    void supportsLegacyAliases() throws Exception {
        String key = "legacy-" + UUID.randomUUID();
        String xml = "<legacy><status>ok</status></legacy>";

        mockMvc.perform(post("/add/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/fetch/{key}", key))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<legacy>")));

        mockMvc.perform(get("/fetchAll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").value(hasItem(key)));

        mockMvc.perform(get("/help"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/responses")));
    }

    @Test
    void rejectsInvalidKeysWithJsonError() throws Exception {
        mockMvc.perform(get("/api/responses/{key}", "bad key"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(containsString("Invalid response key")));

        mockMvc.perform(get("/fetch/{key}", "bad key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Invalid response key")));
    }

    @Test
    void rejectsInvalidXmlAcrossModernAndLegacyWrites() throws Exception {
        mockMvc.perform(post("/api/responses/invalid-modern")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<response>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(containsString("well-formed XML document or fragment")));

        mockMvc.perform(post("/add/invalid-legacy")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(" \n\t"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("XML response content must not be blank."));
    }

    @Test
    void preservesLegacyCompatibleXmlFragments() throws Exception {
        String key = "fragment-" + UUID.randomUUID();
        String fragment = "<MatDate>1722497647</MatDate><AmountRedeem>100</AmountRedeem>";

        mockMvc.perform(post("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(fragment))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/responses/{key}", key))
                .andExpect(status().isOk())
                .andExpect(content().string(fragment));
    }
}
