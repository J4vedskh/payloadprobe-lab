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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class WriteAuthenticationFilter extends OncePerRequestFilter {

    public static final String UNAUTHORIZED_MESSAGE = "Write authentication required.";
    public static final String AUTHENTICATION_DECISION_ATTRIBUTE =
            WriteAuthenticationFilter.class.getName() + ".authenticationDecision";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String WWW_AUTHENTICATE_VALUE = "Bearer realm=\"PayloadProbe writes\"";
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
        String routeTemplate = ProtectedWriteRouteClassifier.classify(request);
        if (routeTemplate == null) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthenticationDecision decision = !enabled
                ? AuthenticationDecision.NOT_REQUIRED
                : hasValidCredential(request)
                        ? AuthenticationDecision.ACCEPTED
                        : AuthenticationDecision.REJECTED;
        request.setAttribute(AUTHENTICATION_DECISION_ATTRIBUTE, decision);
        if (decision != AuthenticationDecision.REJECTED) {
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

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    public enum AuthenticationDecision {
        NOT_REQUIRED("not_required"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        UNDETERMINED("undetermined");

        private final String value;

        AuthenticationDecision(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
