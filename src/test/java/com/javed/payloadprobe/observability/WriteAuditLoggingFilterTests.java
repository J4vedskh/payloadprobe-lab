package com.javed.payloadprobe.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.javed.payloadprobe.security.WriteAuthenticationFilter;
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

class WriteAuditLoggingFilterTests {

    private static final String PATH_SECRET = "private-write-key";
    private static final String BODY_SECRET = "write-body-secret";
    private static final String QUERY_SECRET = "write-query-secret";
    private static final String HEADER_SECRET = "write-header-secret";
    private static final String COOKIE_SECRET = "write-cookie-secret";
    private static final String ADDRESS_SECRET = "198.51.100.77";

    private final WriteAuditLoggingFilter filter = new WriteAuditLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WriteAuditLoggingFilter.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureAuditEvents() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingAuditEvents() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void recordsOneBoundedAcceptedWriteEventWithoutRequestValues() throws Exception {
        MockHttpServletRequest request = sensitiveWriteRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    WriteAuthenticationFilter.AUTHENTICATION_DECISION_ATTRIBUTE,
                    WriteAuthenticationFilter.AuthenticationDecision.ACCEPTED);
            ((HttpServletResponse) servletResponse).setStatus(201);
        });

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo(WriteAuditLoggingFilter.LOG_MESSAGE);
        assertThat(fields(event))
                .containsEntry("event", WriteAuditLoggingFilter.EVENT_NAME)
                .containsEntry("method", "POST")
                .containsEntry("route", "/api/responses/{key}")
                .containsEntry("authentication", "accepted")
                .containsEntry("status", 201)
                .containsEntry("outcome", "success")
                .hasSize(6);
        assertSafe(event);
    }

    @Test
    void recordsDisabledAndRejectedAuthenticationAsBoundedDecisions() throws Exception {
        assertDecision(WriteAuthenticationFilter.AuthenticationDecision.NOT_REQUIRED, 201, "not_required", Level.INFO);
        assertDecision(WriteAuthenticationFilter.AuthenticationDecision.REJECTED, 401, "rejected", Level.WARN);
        assertDecision(WriteAuthenticationFilter.AuthenticationDecision.UNDETERMINED, 503, "undetermined", Level.ERROR);
    }

    @Test
    void recordsDownstreamClientErrorsWithoutCallingThemSuccessful() throws Exception {
        MockHttpServletRequest request = sensitiveWriteRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    WriteAuthenticationFilter.AUTHENTICATION_DECISION_ATTRIBUTE,
                    WriteAuthenticationFilter.AuthenticationDecision.ACCEPTED);
            ((HttpServletResponse) servletResponse).setStatus(409);
        });

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(fields(event))
                .containsEntry("authentication", "accepted")
                .containsEntry("status", 409)
                .containsEntry("outcome", "client_error");
        assertSafe(event);
    }

    @Test
    void recordsUnhandledFailuresAsSafeServerErrorsWithoutThrowableDetails() {
        MockHttpServletRequest request = sensitiveWriteRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                    servletRequest.setAttribute(
                            WriteAuthenticationFilter.AUTHENTICATION_DECISION_ATTRIBUTE,
                            WriteAuthenticationFilter.AuthenticationDecision.ACCEPTED);
                    throw new ServletException("audit-exception-secret");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessage("audit-exception-secret");

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(fields(event))
                .containsEntry("authentication", "accepted")
                .containsEntry("status", 500)
                .containsEntry("outcome", "server_error");
        assertThat(event.getFormattedMessage() + fields(event))
                .doesNotContain("audit-exception-secret", "ServletException");
        assertThat(event.getThrowableProxy()).isNull();
        assertSafe(event);
    }

    @Test
    void ignoresPublicAndUnknownRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fetch/public-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(appender.list).isEmpty();
    }

    private void assertDecision(
            WriteAuthenticationFilter.AuthenticationDecision decision,
            int status,
            String expectedValue,
            Level expectedLevel) throws Exception {
        appender.list.clear();
        MockHttpServletRequest request = sensitiveWriteRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(WriteAuthenticationFilter.AUTHENTICATION_DECISION_ATTRIBUTE, decision);
            ((HttpServletResponse) servletResponse).setStatus(status);
        });

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(expectedLevel);
        assertThat(fields(event))
                .containsEntry("authentication", expectedValue)
                .containsEntry("status", status);
        assertSafe(event);
    }

    private MockHttpServletRequest sensitiveWriteRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/responses/" + PATH_SECRET);
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
