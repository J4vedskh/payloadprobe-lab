package com.javed.payloadprobe.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.store.PayloadScenarioStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "payloadprobe.store.path=target/test-data/scenario-responses-${random.uuid}.json",
        "payloadprobe.scenarios.path=target/test-data/scenarios-${random.uuid}.json",
        "payloadprobe.scenarios.max-scenarios=100",
        "payloadprobe.scenarios.max-response-keys=2",
        "payloadprobe.scenarios.max-document-bytes=512",
        "payloadprobe.response-sets.max-document-bytes=16384",
        "management.health.diskspace.enabled=false"
})
@AutoConfigureMockMvc
class PayloadScenarioTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PayloadScenarioStore scenarioStore;

    @Test
    void createsReplacesAndResolvesAnOrderedScenario() throws Exception {
        String name = "ordered-" + UUID.randomUUID();

        mockMvc.perform(put("/api/scenarios/{name}", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("openTest", "FdLiquidatTestMock")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.responseCount").value(2));

        mockMvc.perform(get("/api/scenarios/{name}", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.responses[0].key").value("openTest"))
                .andExpect(jsonPath("$.responses[0].xml").value("<Open>15</Open>"))
                .andExpect(jsonPath("$.responses[1].key").value("FdLiquidatTestMock"));

        mockMvc.perform(put("/api/scenarios/{name}", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("NtbtdMockTest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCount").value(1));

        mockMvc.perform(get("/api/scenarios/{name}", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses.length()").value(1))
                .andExpect(jsonPath("$.responses[0].key").value("NtbtdMockTest"));
    }

    @Test
    void listsOnlyDeterministicScenarioMetadata() throws Exception {
        String suffix = UUID.randomUUID().toString();
        putScenario("z-" + suffix, "openTest");
        putScenario("a-" + suffix, "FdLiquidatTestMock");

        MvcResult result = mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        PayloadScenarioCatalogResponse catalog = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), PayloadScenarioCatalogResponse.class);
        List<String> names = catalog.scenarios().stream().map(PayloadScenarioSummary::name).toList();
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertThat(names).isEqualTo(sorted);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("<Open>", "xml", "responseKeys");
    }

    @Test
    void deletesAScenarioAndReturnsFixedNotFoundErrors() throws Exception {
        String name = "delete-" + UUID.randomUUID();
        putScenario(name, "openTest");

        mockMvc.perform(delete("/api/scenarios/{name}", name))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/scenarios/{name}", name))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.NOT_FOUND_MESSAGE));
        mockMvc.perform(delete("/api/scenarios/{name}", name))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.NOT_FOUND_MESSAGE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsInvalidDocumentsWithoutCreatingAScenario(String ignoredDescription, String document) throws Exception {
        String name = "invalid-" + UUID.randomUUID();

        mockMvc.perform(put("/api/scenarios/{name}", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(document))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.INVALID_DOCUMENT_MESSAGE));

        assertThat(scenarioStore.findByName(name)).isEmpty();
    }

    @Test
    void rejectsInvalidNamesAndUnavailableResponseReferences() throws Exception {
        mockMvc.perform(put("/api/scenarios/{name}", "bad name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("openTest")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.INVALID_DOCUMENT_MESSAGE));

        String name = "missing-ref-" + UUID.randomUUID();
        mockMvc.perform(put("/api/scenarios/{name}", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("missing-response")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        PayloadScenarioExceptionHandler.UNAVAILABLE_RESPONSE_MESSAGE));
        assertThat(scenarioStore.findByName(name)).isEmpty();
    }

    @Test
    void rejectsOversizedAndWrongMediaTypeRequestsWithFixedErrors() throws Exception {
        mockMvc.perform(put("/api/scenarios/missing-body")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.INVALID_DOCUMENT_MESSAGE));

        mockMvc.perform(put("/api/scenarios/oversized")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseKeys\":[\"" + "a".repeat(600) + "\"]}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.TOO_LARGE_MESSAGE));

        mockMvc.perform(put("/api/scenarios/wrong-media")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(
                        PayloadScenarioExceptionHandler.UNSUPPORTED_MEDIA_TYPE_MESSAGE));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void staleScenarioReturnsConflictWithoutPartialXml() throws Exception {
        String name = "stale-" + UUID.randomUUID();
        putScenario(name, "openTest", "FdLiquidatTestMock");

        mockMvc.perform(delete("/api/responses/{key}", "FdLiquidatTestMock"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/scenarios/{name}", name))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(
                        PayloadScenarioExceptionHandler.UNAVAILABLE_RESPONSE_MESSAGE))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<Open>"))));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void oversizedResolvedScenarioReturnsItsOwnFixedConflict() throws Exception {
        String responseKey = "large-" + UUID.randomUUID();
        String scenarioName = "large-scenario-" + UUID.randomUUID();
        String xml = "<response>" + "x".repeat(17000) + "</response>";
        mockMvc.perform(post("/api/responses/{key}", responseKey)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isCreated());
        putScenario(scenarioName, responseKey);

        mockMvc.perform(get("/api/scenarios/{name}", scenarioName))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.UNRESOLVABLE_MESSAGE))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(responseKey))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<response>"))));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void resolvedScenarioRoundTripsThroughTheResponseSetImporter() throws Exception {
        String name = "roundtrip-" + UUID.randomUUID();
        putScenario(name, "openTest");
        byte[] responseSet = mockMvc.perform(get("/api/scenarios/{name}", name))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        mockMvc.perform(delete("/api/responses/{key}", "openTest"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(responseSet))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1));
        mockMvc.perform(get("/api/responses/openTest"))
                .andExpect(status().isOk())
                .andExpect(content().string("<Open>15</Open>"));
    }

    private void putScenario(String name, String... keys) throws Exception {
        mockMvc.perform(put("/api/scenarios/{name}", name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(keys)))
                .andExpect(status().isCreated());
    }

    private byte[] request(String... keys) throws Exception {
        return objectMapper.writeValueAsBytes(new PayloadScenarioRequest(List.of(keys)));
    }

    private static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                Arguments.of("missing field", "{}"),
                Arguments.of("malformed JSON", "{\"responseKeys\":"),
                Arguments.of("unknown field", "{\"responseKeys\":[\"openTest\"],\"extra\":true}"),
                Arguments.of("duplicate field", "{\"responseKeys\":[\"openTest\"],\"responseKeys\":[\"openTest\"]}"),
                Arguments.of("trailing document", "{\"responseKeys\":[\"openTest\"]} {}"),
                Arguments.of("empty list", "{\"responseKeys\":[]}"),
                Arguments.of("duplicate key", "{\"responseKeys\":[\"openTest\",\"openTest\"]}"),
                Arguments.of("invalid key", "{\"responseKeys\":[\"bad key\"]}"),
                Arguments.of("too many keys", "{\"responseKeys\":[\"openTest\",\"FdLiquidatTestMock\",\"NtbtdMockTest\"]}"));
    }
}
