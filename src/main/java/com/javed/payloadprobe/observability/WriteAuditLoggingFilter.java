package com.javed.payloadprobe.observability;

import com.javed.payloadprobe.security.ProtectedWriteRouteClassifier;
import com.javed.payloadprobe.security.WriteAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public final class WriteAuditLoggingFilter extends OncePerRequestFilter {

    static final String EVENT_NAME = "protected_write_completed";
    static final String LOG_MESSAGE = "write.audit.completed";

    private static final Logger LOGGER = LoggerFactory.getLogger(WriteAuditLoggingFilter.class);
    private static final String OTHER = "other";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String route = ProtectedWriteRouteClassifier.classify(request);
        if (route == null) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            int status = failed ? Math.max(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus())
                    : response.getStatus();
            WriteAuthenticationFilter.AuthenticationDecision authentication = authenticationDecision(request);
            logSafely(request.getMethod(), route, authentication, status);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    private static WriteAuthenticationFilter.AuthenticationDecision authenticationDecision(
            HttpServletRequest request) {
        Object attribute = request.getAttribute(WriteAuthenticationFilter.AUTHENTICATION_DECISION_ATTRIBUTE);
        return attribute instanceof WriteAuthenticationFilter.AuthenticationDecision decision
                ? decision
                : WriteAuthenticationFilter.AuthenticationDecision.UNDETERMINED;
    }

    private static void logSafely(
            String method,
            String route,
            WriteAuthenticationFilter.AuthenticationDecision authentication,
            int status) {
        try {
            String outcome = outcome(status);
            LoggingEventBuilder event = switch (outcome) {
                case "client_error" -> LOGGER.atWarn();
                case "server_error" -> LOGGER.atError();
                default -> LOGGER.atInfo();
            };
            event.addKeyValue("event", EVENT_NAME)
                    .addKeyValue("method", canonicalMethod(method))
                    .addKeyValue("route", route)
                    .addKeyValue("authentication", authentication.value())
                    .addKeyValue("status", status)
                    .addKeyValue("outcome", outcome)
                    .log(LOG_MESSAGE);
        } catch (RuntimeException ignored) {
            // Audit logging is best effort and must never change the HTTP result.
        }
    }

    private static String canonicalMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        return switch (method) {
            case "POST", "PUT", "DELETE" -> method;
            default -> "OTHER";
        };
    }

    private static String outcome(int status) {
        if (status >= 200 && status < 400) {
            return "success";
        }
        if (status >= 400 && status < 500) {
            return "client_error";
        }
        if (status >= 500) {
            return "server_error";
        }
        return OTHER;
    }
}
