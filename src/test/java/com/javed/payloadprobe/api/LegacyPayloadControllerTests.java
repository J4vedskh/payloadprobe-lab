package com.javed.payloadprobe.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.store.PayloadResponseStore;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(LegacyPayloadController.class)
class LegacyPayloadControllerTests {

    private static final String XML = "<legacy><status>ok</status></legacy>";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PayloadResponseStore store;

    @Test
    void fetchesExistingResponseThroughGetAndPostAliases() throws Exception {
        when(store.findByKey("known")).thenReturn(Optional.of(XML));

        mockMvc.perform(get("/fetch/{key}", "known"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(XML));

        mockMvc.perform(post("/fetch/{key}", "known"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(XML));
    }

    @Test
    void preservesLegacyFailureXmlWith200ForMissingFetches() throws Exception {
        when(store.findByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/fetch/{key}", "missing"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<status>failure</status>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No XML response found")));

        mockMvc.perform(post("/fetch/{key}", "missing"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<status>failure</status>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No XML response found")));
    }

    @Test
    void addsNewResponseAndRejectsDuplicate() throws Exception {
        when(store.create("new-key", XML)).thenReturn(true);
        when(store.create("existing", XML)).thenReturn(false);

        mockMvc.perform(post("/add/{key}", "new-key")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Response added for the SPI: new-key"));

        mockMvc.perform(post("/add/{key}", "existing")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(
                        "Response for the given key is already present, try update or delete commands"));
    }

    @Test
    void updatesExistingResponseAndReportsMissingKey() throws Exception {
        when(store.update("known", XML)).thenReturn(true);
        when(store.update("missing", XML)).thenReturn(false);

        mockMvc.perform(post("/update/{key}", "known")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Response updated for the SPI: known"));

        mockMvc.perform(post("/update/{key}", "missing")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(
                        "Response for the given key does not exist, try add command"));
    }

    @Test
    void deletesExistingResponseAndReportsMissingKey() throws Exception {
        when(store.delete("known")).thenReturn(true);
        when(store.delete("missing")).thenReturn(false);

        mockMvc.perform(delete("/delete/{key}", "known"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Response removed for the SPI: known"));

        mockMvc.perform(delete("/delete/{key}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(
                        "Response for the given key does not exist, or has been deleted already"));
    }

    @Test
    void servesDefaultXmlThroughGetAndPostAliases() throws Exception {
        mockMvc.perform(get("/default"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Default XML response")));

        mockMvc.perform(post("/default"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Default XML response")));
    }

    @Test
    void fetchAllReturnsCatalogContract() throws Exception {
        when(store.keys()).thenReturn(List.of("alpha", "zeta"));

        mockMvc.perform(get("/fetchAll"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.keys[0]").value("alpha"))
                .andExpect(jsonPath("$.keys[1]").value("zeta"));
    }

    @Test
    void helpListsEveryLegacyAlias() throws Exception {
        MvcResult result = mockMvc.perform(get("/help"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        ApiHelpResponse help = objectMapper.readValue(result.getResponse().getContentAsByteArray(), ApiHelpResponse.class);

        assertThat(help.endpoints())
                .extracting(ApiEndpoint::method, ApiEndpoint::path)
                .contains(
                        tuple("GET", "/fetch/{key}"),
                        tuple("POST", "/fetch/{key}"),
                        tuple("POST", "/add/{key}"),
                        tuple("POST", "/update/{key}"),
                        tuple("DELETE", "/delete/{key}"),
                        tuple("GET", "/fetchAll"),
                        tuple("GET", "/default"),
                        tuple("POST", "/default"),
                        tuple("GET", "/help"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLegacyKeyRequests")
    void rejectsInvalidKeysForEveryKeyBearingAlias(
            String ignoredDescription, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(ApiExceptionHandler.INVALID_KEY_MESSAGE));
    }

    private static Stream<Arguments> invalidLegacyKeyRequests() {
        return Stream.of(
                Arguments.of("GET /fetch/{key}", get("/fetch/{key}", "bad key")),
                Arguments.of("POST /fetch/{key}", post("/fetch/{key}", "bad key")),
                Arguments.of("POST /add/{key}", post("/add/{key}", "bad key")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML)),
                Arguments.of("POST /update/{key}", post("/update/{key}", "bad key")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(XML)),
                Arguments.of("DELETE /delete/{key}", delete("/delete/{key}", "bad key")));
    }
}
