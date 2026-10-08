package com.javed.payloadprobe.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
public final class RequestCompletionLoggingFilter extends OncePerRequestFilter {

    static final String EVENT_NAME = "http_request_completed";
    static final String LOG_MESSAGE = "request.completed";

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCompletionLoggingFilter.class);
    private static final String OTHER = "other";
    private static final Set<String> SAFE_ROUTE_TEMPLATES = Set.of(
            "/api/responses",
            "/api/responses/{key}",
            "/api/responses/default",
            "/fetch/{key}",
            "/add/{key}",
            "/update/{key}",
            "/delete/{key}",
            "/fetchAll",
            "/default",
            "/help");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            int status = failed ? Math.max(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getStatus())
                    : response.getStatus();
            logCompletion(canonicalMethod(request.getMethod()), safeRouteTemplate(request), status, elapsedMillis);
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

    private static String canonicalMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        return switch (method) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" -> method;
            default -> "OTHER";
        };
    }

    private static String safeRouteTemplate(HttpServletRequest request) {
        Object routeAttribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (routeAttribute == null) {
            return OTHER;
        }
        String candidate = routeAttribute.toString();
        return SAFE_ROUTE_TEMPLATES.contains(candidate) ? candidate : OTHER;
    }

    private static void logCompletion(String method, String route, int status, long elapsedMillis) {
        String outcome = outcome(status);
        LoggingEventBuilder event = switch (outcome) {
            case "client_error" -> LOGGER.atWarn();
            case "server_error" -> LOGGER.atError();
            default -> LOGGER.atInfo();
        };
        event.addKeyValue("event", EVENT_NAME)
                .addKeyValue("method", method)
                .addKeyValue("route", route)
                .addKeyValue("status", status)
                .addKeyValue("outcome", outcome)
                .addKeyValue("duration_ms", elapsedMillis)
                .log(LOG_MESSAGE);
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
