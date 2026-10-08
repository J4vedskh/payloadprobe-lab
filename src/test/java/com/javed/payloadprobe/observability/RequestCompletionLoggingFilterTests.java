package com.javed.payloadprobe.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestCompletionLoggingFilterTests {

    private static final String PATH_SECRET = "private-customer-key";
    private static final String BODY_SECRET = "body-secret";
    private static final String QUERY_SECRET = "query-secret";
    private static final String HEADER_SECRET = "header-secret";
    private static final String COOKIE_SECRET = "cookie-secret";
    private static final String ADDRESS_SECRET = "198.51.100.42";

    private final RequestCompletionLoggingFilter filter = new RequestCompletionLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestCompletionLoggingFilter.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureRequestEvents() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingRequestEvents() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsOnlyBoundedMetadataForMappedRequests() throws Exception {
        MockHttpServletRequest request = sensitiveRequest("POST", "/api/responses/" + PATH_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/responses/{key}");
            ((HttpServletResponse) servletResponse).setStatus(201);
        });

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo(RequestCompletionLoggingFilter.LOG_MESSAGE);
        assertThat(fields(event))
                .containsEntry("event", RequestCompletionLoggingFilter.EVENT_NAME)
                .containsEntry("method", "POST")
                .containsEntry("route", "/api/responses/{key}")
                .containsEntry("status", 201)
                .containsEntry("outcome", "success")
                .containsKey("duration_ms")
                .hasSize(6);
        assertSafe(event);
    }

    @Test
    void classifiesMappedClientErrorsWithoutLoggingRequestValues() throws Exception {
        MockHttpServletRequest request = sensitiveRequest("POST", "/api/responses/" + PATH_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                    "/api/responses/{key}");
            ((HttpServletResponse) servletResponse).setStatus(400);
        });

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(fields(event))
                .containsEntry("route", "/api/responses/{key}")
                .containsEntry("status", 400)
                .containsEntry("outcome", "client_error");
        assertSafe(event);
    }

    @Test
    void preservesTheLegacyMissingFetchAsA200SuccessEvent() throws Exception {
        MockHttpServletRequest request = sensitiveRequest("GET", "/fetch/" + PATH_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/fetch/{key}");
            ((HttpServletResponse) servletResponse).setStatus(200);
        });

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(fields(event))
                .containsEntry("method", "GET")
                .containsEntry("route", "/fetch/{key}")
                .containsEntry("status", 200)
                .containsEntry("outcome", "success");
        assertSafe(event);
    }

    @Test
    void collapsesUnknownRoutesAndMethodsToOther() throws Exception {
        MockHttpServletRequest request = sensitiveRequest("TRACE", "/unknown/" + PATH_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse).setStatus(404));

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(fields(event))
                .containsEntry("method", "OTHER")
                .containsEntry("route", "other")
                .containsEntry("status", 404)
                .containsEntry("outcome", "client_error");
        assertSafe(event);
    }

    @Test
    void logsUnhandledFailuresWithoutAttachingOrFormattingTheException() {
        MockHttpServletRequest request = sensitiveRequest("GET", "/fetch/" + PATH_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                    servletRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/fetch/{key}");
                    throw new ServletException("exception-secret");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessage("exception-secret");

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(fields(event))
                .containsEntry("route", "/fetch/{key}")
                .containsEntry("status", 500)
                .containsEntry("outcome", "server_error");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage() + " " + fields(event))
                .doesNotContain("exception-secret", "ServletException");
        assertSafe(event);
    }

    @Test
    void logsJvmErrorsAsSafeServerErrors() {
        MockHttpServletRequest request = sensitiveRequest("GET", "/fetch/" + PATH_SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                    servletRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/fetch/{key}");
                    throw new AssertionError("jvm-error-secret");
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessage("jvm-error-secret");

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(fields(event))
                .containsEntry("status", 500)
                .containsEntry("outcome", "server_error");
        assertThat(event.getFormattedMessage() + " " + fields(event))
                .doesNotContain("jvm-error-secret", "AssertionError");
        assertThat(event.getThrowableProxy()).isNull();
        assertSafe(event);
    }

    private MockHttpServletRequest sensitiveRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString("token=" + QUERY_SECRET);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + HEADER_SECRET);
        request.setCookies(new Cookie("session", COOKIE_SECRET));
        request.setRemoteAddr(ADDRESS_SECRET);
        request.setContent(("<response><token>" + BODY_SECRET + "</token></response>")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private void assertSafe(ILoggingEvent event) {
        String logged = event.getFormattedMessage() + " " + fields(event).values() + " " + event.getMDCPropertyMap();
        assertThat(logged).doesNotContain(
                PATH_SECRET,
                BODY_SECRET,
                QUERY_SECRET,
                HEADER_SECRET,
                COOKIE_SECRET,
                ADDRESS_SECRET);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getMDCPropertyMap()).isEmpty();
    }
}
