package com.javed.payloadprobe.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

public final class ProtectedWriteRouteClassifier {

    private static final UrlPathHelper URL_PATH_HELPER = pathHelper();

    private ProtectedWriteRouteClassifier() {
    }

    public static String classify(HttpServletRequest request) {
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
}
