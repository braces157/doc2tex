package dev.doc2tex;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in hosting, session isolation, retention, and rate-limit settings for public demos. */
@ConfigurationProperties("doc2tex.hosting")
public record HostingProperties(
        String allowedHosts,
        boolean demoMode,
        boolean trustProxyIp,
        int retentionMinutes,
        int maxJobs,
        int maxJobsPerSession,
        int uploadsPerWindow,
        int previewsPerWindow,
        int rateLimitWindowMinutes) {
    private static final Pattern HOSTNAME = Pattern.compile("^[a-z0-9.-]+$");

    public HostingProperties {
        allowedHosts = allowedHosts == null ? "" : allowedHosts.trim();
        retentionMinutes = retentionMinutes <= 0 ? 60 : retentionMinutes;
        maxJobs = maxJobs <= 0 ? 50 : maxJobs;
        maxJobsPerSession = maxJobsPerSession <= 0 ? 10 : maxJobsPerSession;
        uploadsPerWindow = uploadsPerWindow <= 0 ? 10 : uploadsPerWindow;
        previewsPerWindow = previewsPerWindow <= 0 ? 20 : previewsPerWindow;
        rateLimitWindowMinutes = rateLimitWindowMinutes <= 0 ? 10 : rateLimitWindowMinutes;
    }

    static HostingProperties localDefaults() {
        return new HostingProperties("", false, false, 60, 50, 10, 10, 20, 10);
    }

    Set<String> extraAllowedHosts() {
        if (allowedHosts.isEmpty()) return Set.of();
        return Arrays.stream(allowedHosts.split(","))
                .map(String::trim)
                .map(host -> host.toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .peek(host -> {
                    if (!HOSTNAME.matcher(host).matches() || host.startsWith(".") || host.endsWith(".") || host.contains("..")) {
                        throw new IllegalArgumentException("Invalid allowed host: " + host);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean hosted() {
        return !extraAllowedHosts().isEmpty();
    }
}
