package com.javed.payloadprobe.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.config.PayloadWriteAuthenticationProperties;
import jakarta.validation.Validation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WriteAuthenticationFilterTests {

    private static final String TOKEN = "test-write-token-1234567890";

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    void classifiesOnlyTheNineWriteRouteTemplates(String method, String path, String expectedTemplate) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (path.startsWith("/context/")) {
            request.setContextPath("/context");
        }

        assertThat(WriteAuthenticationFilter.protectedRouteTemplate(request)).isEqualTo(expectedTemplate);
    }

    @ParameterizedTest
    @MethodSource("publicRoutes")
    void leavesReadAndUnknownRoutesPublic(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);

        assertThat(WriteAuthenticationFilter.protectedRouteTemplate(request)).isNull();
    }

    @Test
    void rejectsMissingMalformedDuplicateAndIncorrectCredentialsIdentically() throws Exception {
        WriteAuthenticationFilter filter = enabledFilter();
        for (CredentialCase credentialCase : CredentialCase.values()) {
            MockHttpServletRequest request = protectedRequest();
            credentialCase.apply(request);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

            assertThat(invoked).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Bearer realm=\"PayloadProbe writes\"");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(response.getContentAsString())
                    .isEqualTo("{\"message\":\"Write authentication required.\"}")
                    .doesNotContain(TOKEN, credentialCase.secret);
        }
    }

    @Test
    void acceptsOneCaseInsensitiveBearerSchemeWithTheExactToken() throws Exception {
        WriteAuthenticationFilter filter = enabledFilter();
        MockHttpServletRequest request = protectedRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void disabledAuthenticationNeverRequiresACredential() throws Exception {
        PayloadWriteAuthenticationProperties properties = new PayloadWriteAuthenticationProperties();
        WriteAuthenticationFilter filter = new WriteAuthenticationFilter(properties, new ObjectMapper());
        MockHttpServletRequest request = protectedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
    }

    @Test
    void enabledConfigurationRequiresAValidBoundedBearerToken() {
        PayloadWriteAuthenticationProperties properties = new PayloadWriteAuthenticationProperties();
        properties.setEnabled(true);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String invalidToken : List.of(
                    "",
                    "a".repeat(15),
                    "contains internal whitespace",
                    "a".repeat(257))) {
                properties.setBearerToken(invalidToken);
                assertThat(factory.getValidator().validate(properties))
                        .extracting(violation -> violation.getMessage())
                        .containsExactly("write authentication requires a 16-256 character Bearer token");
            }

            for (String validToken : List.of("a".repeat(16), TOKEN, "z".repeat(256))) {
                properties.setBearerToken(validToken);
                assertThat(factory.getValidator().validate(properties)).isEmpty();
            }
        }

        properties.setBearerToken("too short");
        assertThatThrownBy(() -> new WriteAuthenticationFilter(properties, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Write authentication requires a 16-256 character Bearer token.");
    }

    private WriteAuthenticationFilter enabledFilter() {
        PayloadWriteAuthenticationProperties properties = new PayloadWriteAuthenticationProperties();
        properties.setEnabled(true);
        properties.setBearerToken(TOKEN);
        return new WriteAuthenticationFilter(properties, new ObjectMapper());
    }

    private MockHttpServletRequest protectedRequest() {
        return new MockHttpServletRequest("POST", "/api/responses/private-key");
    }

    private static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                Arguments.of("POST", "/api/responses/key", "/api/responses/{key}"),
                Arguments.of("PUT", "/api/responses/key", "/api/responses/{key}"),
                Arguments.of("DELETE", "/api/responses/key", "/api/responses/{key}"),
                Arguments.of("POST", "/api/response-sets", "/api/response-sets"),
                Arguments.of("PUT", "/api/scenarios/name", "/api/scenarios/{name}"),
                Arguments.of("DELETE", "/api/scenarios/name", "/api/scenarios/{name}"),
                Arguments.of("POST", "/add/key", "/add/{key}"),
                Arguments.of("POST", "/update/key", "/update/{key}"),
                Arguments.of("DELETE", "/delete/key", "/delete/{key}"),
                Arguments.of("POST", "/context/api/responses/key/", "/api/responses/{key}"),
                Arguments.of("POST", "/api/response-sets;source=test", "/api/response-sets"),
                Arguments.of("PUT", "/api/scenarios/name;source=test", "/api/scenarios/{name}"));
    }

    private static Stream<Arguments> publicRoutes() {
        return Stream.of(
                Arguments.of("GET", "/api/responses"),
                Arguments.of("GET", "/api/responses/key"),
                Arguments.of("GET", "/api/responses/key/versions"),
                Arguments.of("POST", "/api/responses/default"),
                Arguments.of("POST", "/api/responses/default;source=test"),
                Arguments.of("POST", "/fetch/key"),
                Arguments.of("POST", "/default"),
                Arguments.of("GET", "/api/response-sets"),
                Arguments.of("GET", "/api/scenarios"),
                Arguments.of("POST", "/api/responses/key/extra"),
                Arguments.of("DELETE", "/delete/key/extra"),
                Arguments.of("POST", "/api/responses/key%2Fextra"),
                Arguments.of("POST", "/unknown"),
                Arguments.of("OPTIONS", "/api/responses/key"));
    }

    private enum CredentialCase {
        MISSING("missing") {
            @Override
            void apply(MockHttpServletRequest request) {
            }
        },
        MALFORMED("malformed-secret") {
            @Override
            void apply(MockHttpServletRequest request) {
                request.addHeader(HttpHeaders.AUTHORIZATION, "Basic malformed-secret");
            }
        },
        DUPLICATE("duplicate-secret") {
            @Override
            void apply(MockHttpServletRequest request) {
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer duplicate-secret");
            }
        },
        DUPLICATE_VALID("duplicate-valid") {
            @Override
            void apply(MockHttpServletRequest request) {
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            }
        },
        EMPTY_BEARER("empty-bearer") {
            @Override
            void apply(MockHttpServletRequest request) {
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");
            }
        },
        INCORRECT("incorrect-secret") {
            @Override
            void apply(MockHttpServletRequest request) {
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer incorrect-secret");
            }
        };

        private final String secret;

        CredentialCase(String secret) {
            this.secret = secret;
        }

        abstract void apply(MockHttpServletRequest request);
    }
}
