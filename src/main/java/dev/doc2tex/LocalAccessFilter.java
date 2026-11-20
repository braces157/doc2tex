package dev.doc2tex;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reject remote hosts/origins in local mode, or restrict to explicitly configured hosts when hosted. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private final Set<String> extraHosts;
    private final boolean hosted;

    LocalAccessFilter() {
        this(HostingProperties.localDefaults());
    }

    @Autowired
    LocalAccessFilter(HostingProperties hosting) {
        this.extraHosts = hosting.extraAllowedHosts();
        this.hosted = !this.extraHosts.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String serverName = normalizeHost(request.getServerName());
        boolean localHost = LOCAL.contains(serverName);
        boolean allowed = localHost || extraHosts.contains(serverName);

        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                String originHost = normalizeHost(uri.getHost());
                if (!Set.of("http", "https").contains(scheme) || originHost.isEmpty()) {
                    allowed = false;
                } else if (localHost) {
                    allowed &= LOCAL.contains(originHost);
                } else {
                    allowed &= originHost.equals(serverName);
                }
            } catch (IllegalArgumentException | NullPointerException exception) {
                allowed = false;
            }
        }

        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            if (!hosted) {
                allowed = false;
            } else {
                String method = request.getMethod();
                String mode = request.getHeader("Sec-Fetch-Mode");
                String path = normalizePath(request);
                boolean safeNavigation = ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                        && "navigate".equalsIgnoreCase(mode)
                        && !path.equals("/api") && !path.startsWith("/api/");
                allowed &= safeNavigation;
            }
        }

        if (!allowed) {
            response.setStatus(403);
            response.setContentType("application/json");
            String message = hosted
                    ? "This workspace rejected a request from an untrusted host or origin."
                    : "This workspace accepts requests from localhost only.";
            response.getWriter().write("{\"message\":\"" + message + "\"}");
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        chain.doFilter(request, response);
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) return "/";
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        StringBuilder cleaned = new StringBuilder(uri.length());
        for (String segment : uri.split("/")) {
            int semi = segment.indexOf(';');
            String part = semi >= 0 ? segment.substring(0, semi) : segment;
            if (!part.isEmpty()) {
                cleaned.append('/').append(part);
            }
        }
        return cleaned.isEmpty() ? "/" : cleaned.toString();
    }
}
