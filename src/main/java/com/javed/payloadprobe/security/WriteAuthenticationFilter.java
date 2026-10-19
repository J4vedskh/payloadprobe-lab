package com.javed.payloadprobe.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.api.MessageResponse;
import com.javed.payloadprobe.config.PayloadWriteAuthenticationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class WriteAuthenticationFilter extends OncePerRequestFilter {

    public static final String UNAUTHORIZED_MESSAGE = "Write authentication required.";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String WWW_AUTHENTICATE_VALUE = "Bearer realm=\"PayloadProbe writes\"";
    private static final UrlPathHelper URL_PATH_HELPER = pathHelper();

    private final boolean enabled;
    private final byte[] expectedDigest;
    private final ObjectMapper objectMapper;

    public WriteAuthenticationFilter(
            PayloadWriteAuthenticationProperties properties,
            ObjectMapper objectMapper) {
        if (!properties.isConfigurationValid()) {
            throw new IllegalArgumentException(
                    "Write authentication requires a 16-256 character Bearer token.");
        }
        this.enabled = properties.isEnabled();
        this.expectedDigest = enabled ? digest(properties.getBearerToken()) : new byte[0];
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String routeTemplate = protectedRouteTemplate(request);
        if (!enabled || routeTemplate == null || hasValidCredential(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, routeTemplate);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), new MessageResponse(UNAUTHORIZED_MESSAGE));
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    private boolean hasValidCredential(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (values == null || !values.hasMoreElements()) {
            return false;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()
                || value == null
                || value.length() <= BEARER_PREFIX.length()
                || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String suppliedToken = value.substring(BEARER_PREFIX.length());
        return MessageDigest.isEqual(expectedDigest, digest(suppliedToken));
    }

    static String protectedRouteTemplate(HttpServletRequest request) {
        String path = applicationPath(request);
        String method = request.getMethod();
        if ("POST".equals(method)) {
            if ("/api/response-sets".equals(path)) {
                return "/api/response-sets";
            }
            if (singleSegment(path, "/api/responses/") && !"/api/responses/default".equals(path)) {
                return "/api/responses/{key}";
            }
            if (singleSegment(path, "/add/")) {
                return "/add/{key}";
            }
            if (singleSegment(path, "/update/")) {
                return "/update/{key}";
            }
        }
        if ("PUT".equals(method)) {
            if (singleSegment(path, "/api/responses/")) {
                return "/api/responses/{key}";
            }
            if (singleSegment(path, "/api/scenarios/")) {
                return "/api/scenarios/{name}";
            }
        }
        if ("DELETE".equals(method)) {
            if (singleSegment(path, "/api/responses/")) {
                return "/api/responses/{key}";
            }
            if (singleSegment(path, "/api/scenarios/")) {
                return "/api/scenarios/{name}";
            }
            if (singleSegment(path, "/delete/")) {
                return "/delete/{key}";
            }
        }
        return null;
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = URL_PATH_HELPER.getPathWithinApplication(request);
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static UrlPathHelper pathHelper() {
        UrlPathHelper pathHelper = new UrlPathHelper();
        pathHelper.setRemoveSemicolonContent(true);
        pathHelper.setUrlDecode(true);
        return pathHelper;
    }

    private static boolean singleSegment(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        String segment = path.substring(prefix.length());
        return !segment.isEmpty() && !segment.contains("/");
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
