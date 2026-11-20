package dev.doc2tex;

import java.util.UUID;
import java.util.regex.Pattern;

/** Per-request thread-local session identifier used when demo session isolation is enabled. */
final class DemoSession {
    static final String COOKIE_NAME = "doc2tex_demo_session";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private DemoSession() {}

    static String current() {
        return CURRENT.get();
    }

    static void set(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) CURRENT.remove();
        else CURRENT.set(sessionId);
    }

    static void clear() {
        CURRENT.remove();
    }

    static boolean isValid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
