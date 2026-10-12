package com.javed.payloadprobe.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.service.PayloadResponseSetService;
import com.javed.payloadprobe.store.PayloadResponseStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "payloadprobe.store.path=target/test-data/response-sets-${random.uuid}.json",
        "payloadprobe.response-sets.max-entries=100",
        "payloadprobe.response-sets.max-document-bytes=16384",
        "management.health.diskspace.enabled=false"
})
@AutoConfigureObservability
@AutoConfigureMockMvc
class PayloadResponseSetTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PayloadResponseStore store;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void exportsAVersionedDeterministicallyOrderedSnapshot() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/response-sets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.responses").isArray())
                .andReturn();

        PayloadResponseSetDocument document = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                PayloadResponseSetDocument.class);
        List<String> keys = document.responses().stream().map(PayloadResponseSetEntry::key).toList();
        List<String> sortedKeys = new ArrayList<>(keys);
        sortedKeys.sort(String::compareTo);
        assertThat(keys).isEqualTo(sortedKeys);
    }

    @Test
    void importsNewResponsesAndPreservesTheirXml() throws Exception {
        String prefix = "set-" + UUID.randomUUID();
        String firstXml = "<first>1</first><second>2</second>";
        String secondXml = "<response><status>ok</status></response>";
        byte[] document = documentBytes(
                new PayloadResponseSetEntry(prefix + "-a", firstXml),
                new PayloadResponseSetEntry(prefix + "-b", secondXml));

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(document))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.imported").value(2));

        mockMvc.perform(get("/api/responses/{key}", prefix + "-a"))
                .andExpect(status().isOk())
                .andExpect(content().string(firstXml));
        mockMvc.perform(get("/api/responses/{key}", prefix + "-b"))
                .andExpect(status().isOk())
                .andExpect(content().string(secondXml));
    }

    @Test
    void rejectsAConflictWithoutCreatingAnyEntry() throws Exception {
        String prefix = "conflict-" + UUID.randomUUID();
        String existingKey = prefix + "-existing";
        String newKey = prefix + "-new";
        String originalXml = "<original/>";

        mockMvc.perform(post("/api/responses/{key}", existingKey)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(originalXml))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBytes(
                                new PayloadResponseSetEntry(existingKey, "<replacement/>"),
                                new PayloadResponseSetEntry(newKey, "<new/>"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(PayloadResponseSetExceptionHandler.CONFLICT_MESSAGE));

        mockMvc.perform(get("/api/responses/{key}", existingKey))
                .andExpect(status().isOk())
                .andExpect(content().string(originalXml));
        mockMvc.perform(get("/api/responses/{key}", newKey))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsInvalidDocumentsWithoutChangingTheCatalog(String ignoredDescription, String document) throws Exception {
        Map<String, String> before = store.snapshot();

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(document))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(PayloadResponseSetExceptionHandler.INVALID_DOCUMENT_MESSAGE));

        assertThat(store.snapshot()).isEqualTo(before);
    }

    @Test
    void rejectsOversizedDocumentsAndWrongMediaTypesWithEndpointSpecificErrors() throws Exception {
        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"responses\":[{\"key\":\"big\",\"xml\":\"<x>"
                                + "a".repeat(17000)
                                + "</x>\"}]}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(PayloadResponseSetExceptionHandler.TOO_LARGE_MESSAGE));

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(
                        PayloadResponseSetExceptionHandler.UNSUPPORTED_MEDIA_TYPE_MESSAGE));

        mockMvc.perform(post("/api/responses/xml-media-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(ApiExceptionHandler.UNSUPPORTED_MEDIA_TYPE_MESSAGE));
    }

    @Test
    void rejectsMoreThanTheConfiguredEntryLimitWithoutChangingTheCatalog() throws Exception {
        Map<String, String> before = store.snapshot();
        List<PayloadResponseSetEntry> entries = new ArrayList<>();
        for (int index = 0; index <= 100; index++) {
            entries.add(new PayloadResponseSetEntry("limit-" + index, "<x/>"));
        }

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new PayloadResponseSetDocument(1, entries))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PayloadResponseSetExceptionHandler.INVALID_DOCUMENT_MESSAGE));

        assertThat(store.snapshot()).isEqualTo(before);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void refusesToExportACatalogBeyondTheConfiguredEntryLimit() throws Exception {
        LinkedHashMap<String, String> oversizedCatalog = new LinkedHashMap<>();
        for (int index = 0; index <= 100; index++) {
            oversizedCatalog.put("export-limit-" + index, "<x/>");
        }
        assertThat(store.createAllIfAbsent(oversizedCatalog)).isTrue();

        mockMvc.perform(get("/api/response-sets"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        PayloadResponseSetExceptionHandler.EXPORT_TOO_LARGE_MESSAGE));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void refusesToExportACatalogBeyondTheConfiguredByteLimit() throws Exception {
        String oversizedXml = "<response>" + "x".repeat(17000) + "</response>";
        assertThat(store.createAllIfAbsent(Map.of("export-byte-limit", oversizedXml))).isTrue();

        mockMvc.perform(get("/api/response-sets"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        PayloadResponseSetExceptionHandler.EXPORT_TOO_LARGE_MESSAGE));
    }

    @Test
    void rejectsAnEmptyDocumentWithoutChangingTheCatalog() throws Exception {
        Map<String, String> before = store.snapshot();

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PayloadResponseSetExceptionHandler.INVALID_DOCUMENT_MESSAGE));

        assertThat(store.snapshot()).isEqualTo(before);
    }

    @Test
    void recordsImportOutcomesWithOnlyBoundedMetricTags() throws Exception {
        double successfulBefore = importCount("success");
        double rejectedBefore = importCount("rejected");
        String key = "metric-" + UUID.randomUUID();

        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(documentBytes(new PayloadResponseSetEntry(key, "<metric/>"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(importCount("success")).isEqualTo(successfulBefore + 1.0);
        assertThat(importCount("rejected")).isEqualTo(rejectedBefore + 1.0);
        assertThat(meterRegistry.find(PayloadResponseSetService.WRITES_METRIC)
                        .tag("operation", "import")
                        .meters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("operation", "outcome"));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("operation=\"import\"")));
    }

    private byte[] documentBytes(PayloadResponseSetEntry... entries) throws Exception {
        return objectMapper.writeValueAsBytes(new PayloadResponseSetDocument(1, List.of(entries)));
    }

    private double importCount(String outcome) {
        return meterRegistry.get(PayloadResponseSetService.WRITES_METRIC)
                .tag("operation", "import")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                Arguments.of("missing fields", "{}"),
                Arguments.of("malformed JSON", "{\"version\":"),
                Arguments.of("unknown field", "{\"version\":1,\"responses\":[],\"extra\":true}"),
                Arguments.of("duplicate JSON field", "{\"version\":1,\"version\":1,\"responses\":[]}"),
                Arguments.of("unsupported version", "{\"version\":2,\"responses\":[{\"key\":\"a\",\"xml\":\"<a/>\"}]}"),
                Arguments.of("empty set", "{\"version\":1,\"responses\":[]}"),
                Arguments.of("duplicate response key", "{\"version\":1,\"responses\":["
                        + "{\"key\":\"duplicate\",\"xml\":\"<a/>\"},"
                        + "{\"key\":\"duplicate\",\"xml\":\"<b/>\"}]}"),
                Arguments.of("invalid response key", "{\"version\":1,\"responses\":["
                        + "{\"key\":\"bad key\",\"xml\":\"<a/>\"}]}"),
                Arguments.of("malformed XML", "{\"version\":1,\"responses\":["
                        + "{\"key\":\"bad-xml\",\"xml\":\"<a>\"}]}"),
                Arguments.of("DTD XML", "{\"version\":1,\"responses\":["
                        + "{\"key\":\"dtd\",\"xml\":\"<!DOCTYPE r><r/>\"}]}"));
    }
}
