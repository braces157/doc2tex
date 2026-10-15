package dev.doc2tex;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reject remote origins and DNS rebinding against this unauthenticated local workspace. */
@Component
class LocalAccessFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean allowed = LOCAL.contains(request.getServerName());
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                allowed &= Set.of("http", "https").contains(uri.getScheme()) && LOCAL.contains(uri.getHost());
            } catch (IllegalArgumentException | NullPointerException exception) { allowed = false; }
        }
        if (!allowed || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"This workspace accepts requests from localhost only.\"}");
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        chain.doFilter(request, response);
    }
}
