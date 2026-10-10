package com.seekwd.dormcheck.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSecurityFilter extends OncePerRequestFilter {
    private final Set<String> allowedHosts;

    public RequestSecurityFilter(@Value("${dormcheck.allowed-host}") String allowedHost) {
        allowedHosts = Arrays.stream(allowedHost.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // Academic responses are user-scoped. Keep them out of the browser's
        // shared HTTP cache; the frontend's user-scoped session cache handles
        // the intentional short-lived reuse.
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'");
        if ("https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")) || request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }

        if (!allowedHosts.isEmpty()) {
            String host = request.getServerName().toLowerCase();
            if (!allowedHosts.contains(host)) {
                response.sendError(HttpStatus.BAD_REQUEST.value());
                return;
            }
        }

        String method = request.getMethod();
        if (Set.of("POST", "PUT", "PATCH", "DELETE").contains(method)) {
            String origin = request.getHeader("Origin");
            String scheme = request.getHeader("X-Forwarded-Proto");
            if (scheme == null || scheme.isBlank()) scheme = request.getScheme();
            String forwardedHost = request.getHeader("X-Forwarded-Host");
            String host = forwardedHost == null || forwardedHost.isBlank() ? request.getHeader("Host") : forwardedHost;
            String expected = scheme.toLowerCase() + "://" + host;
            if (origin == null || !constantTimeEquals(origin.replaceFirst("/$", ""), expected.replaceFirst("/$", ""))) {
                response.sendError(HttpStatus.FORBIDDEN.value(), "request origin rejected");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String first, String second) {
        return java.security.MessageDigest.isEqual(first.getBytes(java.nio.charset.StandardCharsets.UTF_8), second.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
