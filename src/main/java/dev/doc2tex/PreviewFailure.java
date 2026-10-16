package dev.doc2tex;

import java.util.Locale;

final class PreviewFailure {
    static String message(String log) {
        String text = log.toLowerCase(Locale.ROOT);
        if (java.util.List.of("failed to download", "could not resolve", "dns error", "network is unreachable", "error sending request", "connection refused", "connection timed out", "not cached", "not available in the cache")
                .stream().anyMatch(text::contains))
            return "PDF package download/cache access failed. The first build needs internet access to fetch TeX packages. Check your connection/proxy, then retry Build PDF. Source and ZIP export remain available; inspect the compiler log if it persists.";
        return "PDF compilation failed. Check the compiler log, edit the source, and try again.";
    }
}
