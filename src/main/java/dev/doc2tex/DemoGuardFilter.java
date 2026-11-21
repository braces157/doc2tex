package dev.doc2tex;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces per-browser session isolation, capacity guards, and rate limits in public demo mode. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class DemoGuardFilter extends OncePerRequestFilter {
    private final HostingProperties hosting;
    private final JobStore store;
    private final RateLimiter uploads;
    private final RateLimiter previews;

    @Autowired
    DemoGuardFilter(HostingProperties hosting, JobStore store) {
        this(hosting, store, Clock.systemUTC());
    }

    DemoGuardFilter(HostingProperties hosting, JobStore store, Clock clock) {
        this.hosting = hosting;
        this.store = store;
        Duration window = Duration.ofMinutes(hosting.rateLimitWindowMinutes());
        this.uploads = new RateLimiter(hosting.uploadsPerWindow(), window, clock);
        this.previews = new RateLimiter(hosting.previewsPerWindow(), window, clock);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hosting.demoMode()) {
            chain.doFilter(request, response);
            return;
        }

        String path = LocalAccessFilter.normalizePath(request);
        String sessionId = resolveSessionId(request);
        if (sessionId == null) {
            sessionId = DemoSession.newId();
            ResponseCookie cookie = ResponseCookie.from(DemoSession.COOKIE_NAME, sessionId)
                    .path("/")
                    .httpOnly(true)
                    .sameSite("Lax")
                    .secure(request.isSecure())
                    .maxAge(Duration.ofHours(24))
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        if (path.startsWith("/api/") && !path.equals("/api/sample.docx")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        }

        DemoSession.set(sessionId);
        try {
            String jobId = extractJobId(path);
            if (jobId != null && store.exists(jobId) && !store.isOwnedBy(jobId, sessionId)) {
                writeError(response, HttpStatus.NOT_FOUND.value(), "This conversion could not be found.");
                return;
            }

            if ("GET".equalsIgnoreCase(request.getMethod())
                    && path.matches("^/api/jobs/[^/]+/export/[^/]+$")) {
                if (!checkRateLimit(previews, clientIp(request), response,
                        "Demo export rate limit reached. Please wait a few minutes before exporting again.")) {
                    return;
                }
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {
                if (path.equals("/api/jobs") || path.matches("^/api/jobs/[^/]+/layout-copy$")) {
                    if (!checkUploadAccess(request, response, sessionId)) return;
                } else if (path.matches("^/api/jobs/[^/]+/preview$")) {
                    if (!checkRateLimit(previews, clientIp(request), response,
                            "Demo PDF build rate limit reached. Please wait a few minutes before building again.")) {
                        return;
                    }
                } else if (path.startsWith("/api/pdf/")
                        || path.equals("/api/convert")
                        || path.startsWith("/api/convert/")
                        || path.startsWith("/api/diff/")
                        || path.startsWith("/api/docx/")
                        || path.startsWith("/api/templates/")
                        || path.startsWith("/api/references/")
                        || path.startsWith("/api/batch/")) {
                    if (!checkRateLimit(uploads, clientIp(request), response,
                            "Demo workspace rate limit reached. Please wait a few minutes before trying again.")) {
                        return;
                    }
                }
            }

            chain.doFilter(request, response);
        } finally {
            DemoSession.clear();
        }
    }

    private boolean checkUploadAccess(HttpServletRequest request, HttpServletResponse response, String sessionId)
            throws IOException {
        if (!checkRateLimit(uploads, clientIp(request), response,
                "Demo rate limit reached. Please wait a few minutes before starting another conversion.")) {
            return false;
        }
        if (store.sessionCount(sessionId) >= hosting.maxJobsPerSession()) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Demo session limit reached. Delete an older document in All documents to upload another.");
            return false;
        }
        return true;
    }

    private static boolean checkRateLimit(RateLimiter limiter, String key, HttpServletResponse response, String message)
            throws IOException {
        long retryAfter = limiter.tryAcquire(key);
        if (retryAfter <= 0) return true;
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(), message);
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        if (hosting.trustProxyIp()) {
            String forwarded = request.getHeader("CF-Connecting-IP");
            if (forwarded != null) {
                String trimmed = forwarded.trim();
                if (!trimmed.isEmpty() && trimmed.length() <= 64
                        && trimmed.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                    return trimmed;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static String resolveSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (DemoSession.COOKIE_NAME.equals(cookie.getName()) && DemoSession.isValid(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String extractJobId(String path) {
        if (!path.startsWith("/api/jobs/")) return null;
        String rest = path.substring("/api/jobs/".length());
        if (rest.isEmpty()) return null;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    private static final class RateLimiter {
        private static final int MAX_KEYS = 2048;
        private final int limit;
        private final Duration window;
        private final Clock clock;
        private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

        RateLimiter(int limit, Duration window, Clock clock) {
            this.limit = limit;
            this.window = window;
            this.clock = clock;
        }

        long tryAcquire(String key) {
            Instant now = clock.instant();
            Instant cutoff = now.minus(window);
            if (buckets.size() > MAX_KEYS) {
                pruneExpiredKeys(cutoff);
            }
            AtomicLong retryAfter = new AtomicLong(0L);
            buckets.compute(key, (ignored, queue) -> {
                Deque<Instant> timestamps = queue == null ? new ArrayDeque<>() : queue;
                prune(timestamps, cutoff);
                if (timestamps.size() >= limit) {
                    Instant oldest = timestamps.peekFirst();
                    long seconds = oldest == null ? 1 : Duration.between(now, oldest.plus(window)).getSeconds() + 1;
                    retryAfter.set(Math.max(1L, seconds));
                } else {
                    timestamps.addLast(now);
                }
                return timestamps;
            });
            return retryAfter.get();
        }

        private void pruneExpiredKeys(Instant cutoff) {
            for (String key : buckets.keySet()) {
                buckets.computeIfPresent(key, (ignored, timestamps) -> {
                    prune(timestamps, cutoff);
                    return timestamps.isEmpty() ? null : timestamps;
                });
            }
        }

        private static void prune(Deque<Instant> timestamps, Instant cutoff) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.removeFirst();
            }
        }
    }
}
