package com.auracademic.backend.util;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientInfoUtil {
    private ClientInfoUtil() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = firstNonBlank(
                firstForwardedIp(request.getHeader("X-Forwarded-For")),
                request.getHeader("X-Real-IP"),
                request.getHeader("CF-Connecting-IP"),
                request.getHeader("True-Client-IP"),
                firstForwardedIp(request.getHeader("Forwarded")),
                request.getRemoteAddr()
        );

        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip != null ? ip : "unknown";
    }

    public static String getClientDevice(HttpServletRequest request) {
        return getReadableDevice(request.getHeader("User-Agent"));
    }

    public static String getReadableDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }
        if (!userAgent.contains("Mozilla/") && userAgent.contains(" on ") && userAgent.contains("(")) {
            return userAgent;
        }

        String ua = userAgent.toLowerCase();
        String browser = detectBrowser(ua);
        String os = detectOs(ua);
        String deviceType = detectDeviceType(ua);

        return browser + " on " + os + " (" + deviceType + ")";
    }

    private static String firstForwardedIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.split(",")[0].trim();
        if (first.toLowerCase().startsWith("for=")) {
            first = first.substring(4).trim();
        }
        int semicolonIndex = first.indexOf(';');
        if (semicolonIndex > -1) {
            first = first.substring(0, semicolonIndex).trim();
        }
        if (first.startsWith("\"") && first.endsWith("\"") && first.length() > 1) {
            first = first.substring(1, first.length() - 1);
        }
        if (first.startsWith("[") && first.contains("]")) {
            first = first.substring(1, first.indexOf(']'));
        } else {
            int portIndex = first.indexOf(':');
            if (portIndex > -1 && first.indexOf(':', portIndex + 1) == -1) {
                first = first.substring(0, portIndex);
            }
        }
        return first.isBlank() ? null : first;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String detectBrowser(String ua) {
        if (ua.contains("edg/")) return "Microsoft Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("firefox/")) return "Firefox";
        if (ua.contains("chrome/") || ua.contains("crios/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        return "Unknown browser";
    }

    private static String detectOs(String ua) {
        if (ua.contains("windows nt")) return "Windows";
        if (ua.contains("android")) return "Android";
        if (ua.contains("iphone") || ua.contains("ipad")) return "iOS";
        if (ua.contains("mac os x") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("linux")) return "Linux";
        return "Unknown OS";
    }

    private static String detectDeviceType(String ua) {
        if (ua.contains("ipad") || ua.contains("tablet")) return "Tablet";
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("android")) return "Mobile";
        return "Desktop";
    }
}
