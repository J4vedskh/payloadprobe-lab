package com.javed.payloadprobe.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "payloadprobe.store.path=target/test-data/history-api-${random.uuid}.json",
            "payloadprobe.scenarios.path=target/test-data/history-scenarios-${random.uuid}.json",
            "payloadprobe.response-history.max-revisions-per-response=2",
            "management.health.diskspace.enabled=false"
        })
@AutoConfigureMockMvc
class PayloadResponseHistoryTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void listsNewestVersionsAndReturnsExactRetainedXml() throws Exception {
        String key = "history-" + UUID.randomUUID();
        create(key, "<response>one</response>");
        update(key, "<response>two</response>");
        update(key, "<response>three</response>");

        mockMvc.perform(get("/api/responses/{key}/versions", key))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.currentRevision").value(3))
                .andExpect(jsonPath("$.retainedCount").value(2))
                .andExpect(jsonPath("$.versions[0].revision").value(3))
                .andExpect(jsonPath("$.versions[0].current").value(true))
                .andExpect(jsonPath("$.versions[1].revision").value(2))
                .andExpect(jsonPath("$.versions[1].current").value(false));

        mockMvc.perform(get("/api/responses/{key}/versions/{revision}", key, 2))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string("<response>two</response>"));
        mockMvc.perform(get("/api/responses/{key}/versions/{revision}", key, 3))
                .andExpect(status().isOk())
                .andExpect(content().string("<response>three</response>"));
        mockMvc.perform(get("/api/responses/{key}/versions/{revision}", key, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No retained XML response found for revision."));
    }

    @Test
    void returnsStableErrorsForMissingKeysAndInvalidRevisions() throws Exception {
        String missingKey = "missing-" + UUID.randomUUID();

        mockMvc.perform(get("/api/responses/{key}/versions", missingKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No XML response found for key: " + missingKey));
        mockMvc.perform(get("/api/responses/{key}/versions/{revision}", missingKey, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No XML response found for key: " + missingKey));
        mockMvc.perform(get("/api/responses/{key}/versions/{revision}", missingKey, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Invalid response revision. Use a positive whole number."));
        mockMvc.perform(get("/api/responses/{key}/versions/{revision}", "bad key", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ApiExceptionHandler.INVALID_KEY_MESSAGE));
    }

    @Test
    void deletePurgesHistoryAndRecreateStartsAtRevisionOne() throws Exception {
        String key = "lifecycle-" + UUID.randomUUID();
        create(key, "<response>one</response>");
        update(key, "<response>two</response>");

        mockMvc.perform(delete("/api/responses/{key}", key))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/responses/{key}/versions", key))
                .andExpect(status().isNotFound());

        create(key, "<response>new</response>");
        mockMvc.perform(get("/api/responses/{key}/versions", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRevision").value(1))
                .andExpect(jsonPath("$.retainedCount").value(1));
    }

    @Test
    void importAndLegacyUpdatesShareHistoryWhileExportsAndScenariosUseCurrentXml() throws Exception {
        String key = "portable-" + UUID.randomUUID();
        String scenario = "scenario-" + UUID.randomUUID();
        mockMvc.perform(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"responses\":[{\"key\":\"" + key
                                + "\",\"xml\":\"<response>one</response>\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/update/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<response>two</response>"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/responses/{key}/versions", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].revision").value(2))
                .andExpect(jsonPath("$.versions[1].revision").value(1));

        mockMvc.perform(get("/api/response-sets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[?(@.key == '" + key + "')].xml")
                        .value(hasItem("<response>two</response>")));
        mockMvc.perform(put("/api/scenarios/{name}", scenario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseKeys\":[\"" + key + "\"]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/scenarios/{name}", scenario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].xml").value("<response>two</response>"));
    }

    private void create(String key, String xml) throws Exception {
        mockMvc.perform(post("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isCreated());
    }

    private void update(String key, String xml) throws Exception {
        mockMvc.perform(put("/api/responses/{key}", key)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isOk());
    }
}
