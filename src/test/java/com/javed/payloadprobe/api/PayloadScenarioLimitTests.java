package com.javed.payloadprobe.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "payloadprobe.store.path=target/test-data/scenario-limit-responses-${random.uuid}.json",
        "payloadprobe.scenarios.path=target/test-data/scenario-limit-${random.uuid}.json",
        "payloadprobe.scenarios.max-scenarios=1",
        "management.health.diskspace.enabled=false"
})
@AutoConfigureMockMvc
class PayloadScenarioLimitTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void returnsFixedConflictWhenTheScenarioCatalogIsFull() throws Exception {
        byte[] request = objectMapper.writeValueAsBytes(new PayloadScenarioRequest(List.of("openTest")));

        mockMvc.perform(put("/api/scenarios/first")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/scenarios/second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PayloadScenarioExceptionHandler.LIMIT_REACHED_MESSAGE));
    }
}
