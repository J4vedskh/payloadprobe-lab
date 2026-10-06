package com.javed.payloadprobe.api;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javed.payloadprobe.store.PayloadResponseStore;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({PayloadResponsesController.class, LegacyPayloadController.class})
@Import(XmlPayloadValidator.class)
@TestPropertySource(properties = "payloadprobe.validation.max-payload-bytes=64")
class PayloadMutationValidationTests {

    private static final String KEY = "validation-test";
    private static final String VALID_FRAGMENT = "<first>1</first><second>2</second>";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PayloadResponseStore store;

    @ParameterizedTest(name = "{0} rejects a blank payload")
    @MethodSource("mutationRoutes")
    void rejectsBlankPayloads(String ignoredDescription, HttpMethod method, String path) throws Exception {
        expectError(request(method, path)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(" \n\t"),
                400,
                ApiExceptionHandler.BLANK_PAYLOAD_MESSAGE);
    }

    @ParameterizedTest(name = "{0} rejects a missing payload")
    @MethodSource("mutationRoutes")
    void rejectsMissingPayloads(String ignoredDescription, HttpMethod method, String path) throws Exception {
        expectError(request(method, path).contentType(MediaType.APPLICATION_XML),
                400,
                ApiExceptionHandler.BLANK_PAYLOAD_MESSAGE);
    }

    @ParameterizedTest(name = "{0} rejects malformed XML")
    @MethodSource("mutationRoutes")
    void rejectsMalformedPayloads(String ignoredDescription, HttpMethod method, String path) throws Exception {
        expectError(request(method, path)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<response>"),
                400,
                ApiExceptionHandler.MALFORMED_PAYLOAD_MESSAGE);
    }

    @ParameterizedTest(name = "{0} rejects DTDs and external entities")
    @MethodSource("mutationRoutes")
    void rejectsExternalEntityPayloads(String ignoredDescription, HttpMethod method, String path) throws Exception {
        String payload = "<!DOCTYPE r [<!ENTITY x SYSTEM \"x\">]><r>&x;</r>";

        expectError(request(method, path)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(payload),
                400,
                ApiExceptionHandler.MALFORMED_PAYLOAD_MESSAGE);
    }

    @ParameterizedTest(name = "{0} rejects a malformed XML declaration")
    @MethodSource("mutationRoutes")
    void rejectsMalformedXmlDeclarations(String ignoredDescription, HttpMethod method, String path) throws Exception {
        expectError(request(method, path)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<?xml version=\"2.0\"?><response/>"),
                400,
                ApiExceptionHandler.MALFORMED_PAYLOAD_MESSAGE);
    }

    @ParameterizedTest(name = "{0} rejects an oversized payload")
    @MethodSource("mutationRoutes")
    void rejectsOversizedPayloads(String ignoredDescription, HttpMethod method, String path) throws Exception {
        String payload = "<response>" + "x".repeat(64) + "</response>";

        expectError(request(method, path)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(payload),
                400,
                ApiExceptionHandler.OVERSIZED_PAYLOAD_MESSAGE);
    }

    @ParameterizedTest(name = "{0} rejects an unsupported Content-Type")
    @MethodSource("mutationRoutes")
    void rejectsUnsupportedMediaTypes(String ignoredDescription, HttpMethod method, String path) throws Exception {
        expectError(request(method, path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                415,
                ApiExceptionHandler.UNSUPPORTED_MEDIA_TYPE_MESSAGE);
    }

    @ParameterizedTest(name = "{0} accepts and preserves an XML fragment as {3}")
    @MethodSource("validMutationRequests")
    void acceptsAndPreservesValidFragments(
            String ignoredDescription,
            HttpMethod method,
            String path,
            MediaType contentType) throws Exception {
        boolean createRoute = (method == HttpMethod.POST && path.contains("/api/responses/"))
                || path.startsWith("/add/");
        if (createRoute) {
            when(store.create(KEY, VALID_FRAGMENT)).thenReturn(true);
        } else {
            when(store.update(KEY, VALID_FRAGMENT)).thenReturn(true);
        }

        mockMvc.perform(request(method, path)
                        .contentType(contentType)
                        .content(VALID_FRAGMENT))
                .andExpect(createRoute ? status().isCreated() : status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        if (createRoute) {
            verify(store).create(KEY, VALID_FRAGMENT);
        } else {
            verify(store).update(KEY, VALID_FRAGMENT);
        }
    }

    private void expectError(
            MockHttpServletRequestBuilder request,
            int expectedStatus,
            String expectedMessage) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        verifyNoInteractions(store);
    }

    private static Stream<Arguments> mutationRoutes() {
        return Stream.of(
                Arguments.of("modern create", HttpMethod.POST, "/api/responses/" + KEY),
                Arguments.of("modern update", HttpMethod.PUT, "/api/responses/" + KEY),
                Arguments.of("legacy add", HttpMethod.POST, "/add/" + KEY),
                Arguments.of("legacy update", HttpMethod.POST, "/update/" + KEY));
    }

    private static Stream<Arguments> validMutationRequests() {
        return Stream.of(MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN)
                .flatMap(contentType -> Stream.of(
                        Arguments.of("modern create", HttpMethod.POST, "/api/responses/" + KEY, contentType),
                        Arguments.of("modern update", HttpMethod.PUT, "/api/responses/" + KEY, contentType),
                        Arguments.of("legacy add", HttpMethod.POST, "/add/" + KEY, contentType),
                        Arguments.of("legacy update", HttpMethod.POST, "/update/" + KEY, contentType)));
    }
}
