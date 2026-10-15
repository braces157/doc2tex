package dev.doc2tex;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("doc2tex")
public record AppProperties(String dataDir, String pandoc, String tectonic,
                            int workers, int queueCapacity,
                            int conversionTimeoutSeconds, int previewTimeoutSeconds) {}
