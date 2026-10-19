package com.javed.payloadprobe.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.javed.payloadprobe.observability.RequestCompletionLoggingFilter;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
        properties = {
            "payloadprobe.store.path=target/test-data/write-auth-responses-${random.uuid}.json",
            "payloadprobe.scenarios.path=target/test-data/write-auth-scenarios-${random.uuid}.json",
            "payloadprobe.write-auth.enabled=true",
            "payloadprobe.write-auth.bearer-token=test-write-token-1234567890",
            "management.health.diskspace.enabled=false"
        })
@AutoConfigureMockMvc
class WriteAuthenticationIntegrationTests {

    private static final String TOKEN = "test-write-token-1234567890";
    private static final String AUTHORIZATION = "Bearer " + TOKEN;

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void everyWriteRouteRejectsMissingAndIncorrectCredentials(
            String ignoredDescription,
            HttpMethod method,
            String path,
            MediaType contentType,
            String body) throws Exception {
        MockHttpServletRequestBuilder missing = request(method, path);
        if (contentType != null) {
            missing.contentType(contentType).content(body);
        }
        expectUnauthorized(missing);

        MockHttpServletRequestBuilder incorrect = request(method, path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer incorrect-secret-value");
        if (contentType != null) {
            incorrect.contentType(contentType).content(body);
        }
        expectUnauthorized(incorrect);
    }

    @Test
    void validCredentialPreservesEveryWriteContract() throws Exception {
        String modernKey = "auth-modern-" + UUID.randomUUID();
        mockMvc.perform(withCredential(post("/api/responses/{key}", modernKey)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<response>one</response>")))
                .andExpect(status().isCreated());
        mockMvc.perform(withCredential(put("/api/responses/{key}", modernKey)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<response>two</response>")))
                .andExpect(status().isOk());
        mockMvc.perform(withCredential(delete("/api/responses/{key}", modernKey)))
                .andExpect(status().isOk());

        String importedKey = "auth-import-" + UUID.randomUUID();
        mockMvc.perform(withCredential(post("/api/response-sets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"responses\":[{\"key\":\"" + importedKey
                                + "\",\"xml\":\"<response/>\"}]}")))
                .andExpect(status().isCreated());
        String scenario = "auth-scenario-" + UUID.randomUUID();
        mockMvc.perform(withCredential(put("/api/scenarios/{name}", scenario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseKeys\":[\"" + importedKey + "\"]}")))
                .andExpect(status().isCreated());
        mockMvc.perform(withCredential(delete("/api/scenarios/{name}", scenario)))
                .andExpect(status().isNoContent());

        String legacyKey = "auth-legacy-" + UUID.randomUUID();
        mockMvc.perform(withCredential(post("/add/{key}", legacyKey)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<legacy>one</legacy>")))
                .andExpect(status().isCreated());
        mockMvc.perform(withCredential(post("/update/{key}", legacyKey)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<legacy>two</legacy>")))
                .andExpect(status().isOk());
        mockMvc.perform(withCredential(delete("/delete/{key}", legacyKey)))
                .andExpect(status().isOk());
    }

    @Test
    void allReadRoutesAndReadOnlyPostAliasesRemainPublic() throws Exception {
        mockMvc.perform(get("/api/responses"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/response-sets"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/fetch/{key}", "missing-public-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML));
        mockMvc.perform(post("/default"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/responses/default"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/responses/default;source=test"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticationRunsBeforeBodyValidationAndRejectedWritesDoNotMutateState() throws Exception {
        String key = "auth-short-circuit-" + UUID.randomUUID();

        expectUnauthorized(post("/api/responses/{key}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("not XML"));
        mockMvc.perform(get("/api/responses/{key}", key))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/responses/{key}", key)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not XML"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void rejectedCredentialsAreLoggedWithoutCredentialOrPathValues(CapturedOutput output) throws Exception {
        String key = "private-auth-key-" + UUID.randomUUID();
        String wrongToken = "wrong-authorization-secret";
        Logger requestLogger = (Logger) LoggerFactory.getLogger(RequestCompletionLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        requestLogger.addAppender(appender);

        try {
            expectUnauthorized(post("/api/responses/{key}", key)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongToken)
                    .contentType(MediaType.APPLICATION_XML)
                    .content("<response/>"));
        } finally {
            requestLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("request.completed");
            assertThat(event.getKeyValuePairs())
                    .extracting(pair -> pair.key, pair -> pair.value)
                    .contains(
                            org.assertj.core.groups.Tuple.tuple("route", "/api/responses/{key}"),
                            org.assertj.core.groups.Tuple.tuple("status", 401),
                            org.assertj.core.groups.Tuple.tuple("outcome", "client_error"));
            assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                    .doesNotContain(key, wrongToken, TOKEN);
        });
        assertThat(output.getOut()).doesNotContain(key, wrongToken, TOKEN);
    }

    private void expectUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(WriteAuthenticationFilter.UNAUTHORIZED_MESSAGE))
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"PayloadProbe writes\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    private MockHttpServletRequestBuilder withCredential(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, AUTHORIZATION);
    }

    private static Stream<Arguments> protectedRequests() {
        return Stream.of(
                Arguments.of(
                        "modern create",
                        HttpMethod.POST,
                        "/api/responses/auth-modern-protected",
                        MediaType.APPLICATION_XML,
                        "<response/>"),
                Arguments.of(
                        "modern update",
                        HttpMethod.PUT,
                        "/api/responses/auth-modern-protected",
                        MediaType.APPLICATION_XML,
                        "<response/>"),
                Arguments.of(
                        "modern delete",
                        HttpMethod.DELETE,
                        "/api/responses/auth-modern-protected",
                        null,
                        ""),
                Arguments.of(
                        "response-set import",
                        HttpMethod.POST,
                        "/api/response-sets",
                        MediaType.APPLICATION_JSON,
                        "{\"version\":1,\"responses\":[]}"),
                Arguments.of(
                        "scenario put",
                        HttpMethod.PUT,
                        "/api/scenarios/auth-scenario-protected",
                        MediaType.APPLICATION_JSON,
                        "{\"responseKeys\":[\"openTest\"]}"),
                Arguments.of(
                        "scenario delete",
                        HttpMethod.DELETE,
                        "/api/scenarios/auth-scenario-protected",
                        null,
                        ""),
                Arguments.of(
                        "legacy add",
                        HttpMethod.POST,
                        "/add/auth-legacy-protected",
                        MediaType.APPLICATION_XML,
                        "<response/>"),
                Arguments.of(
                        "legacy update",
                        HttpMethod.POST,
                        "/update/auth-legacy-protected",
                        MediaType.APPLICATION_XML,
                        "<response/>"),
                Arguments.of(
                        "legacy delete",
                        HttpMethod.DELETE,
                        "/delete/auth-legacy-protected",
                        null,
                        ""));
    }
}
