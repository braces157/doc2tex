package dev.doc2tex;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** Validated conversion inputs, never interpreted as user-provided TeX. */
public record LayoutOptions(String paper, BigDecimal fontSize, BigDecimal marginMm, String title, String author, String date,
                            boolean preserveSourceLayout, boolean aiLayout) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LayoutOptions.class);

    public LayoutOptions {
        if (!List.of("a4", "letter").contains(paper == null ? "" : paper) || fontSize == null
                || (fontSize.compareTo(BigDecimal.TEN) != 0 && fontSize.compareTo(BigDecimal.valueOf(11)) != 0 && fontSize.compareTo(BigDecimal.valueOf(12)) != 0)
                || marginMm == null || marginMm.compareTo(BigDecimal.TEN) < 0 || marginMm.compareTo(BigDecimal.valueOf(50)) > 0
                || marginMm.stripTrailingZeros().scale() > 1) throw invalid();
        fontSize = fontSize.stripTrailingZeros(); marginMm = marginMm.stripTrailingZeros();
        title = text(title, 200); author = text(author, 200); date = text(date, 80);
    }

    LayoutOptions(String paper, int fontSize, BigDecimal marginMm, String title, String author, String date) {
        this(paper, BigDecimal.valueOf(fontSize), marginMm, title, author, date, false);
    }

    LayoutOptions(String paper, BigDecimal fontSize, BigDecimal marginMm, String title, String author, String date) {
        this(paper, fontSize, marginMm, title, author, date, false);
    }

    LayoutOptions(String paper, BigDecimal fontSize, BigDecimal marginMm, String title, String author, String date, boolean preserveSourceLayout) {
        this(paper, fontSize, marginMm, title, author, date, preserveSourceLayout, false);
    }

    @JsonCreator
    public static LayoutOptions fromJson(@JsonProperty("paper") String paper, @JsonProperty("fontSize") BigDecimal fontSize,
            @JsonProperty("marginMm") BigDecimal marginMm, @JsonProperty("title") String title,
            @JsonProperty("author") String author, @JsonProperty("date") String date,
            @JsonProperty("preserveSourceLayout") Boolean preserveSourceLayout, @JsonProperty("aiLayout") Boolean aiLayout) {
        return new LayoutOptions(paper, fontSize, marginMm, title, author, date, Boolean.TRUE.equals(preserveSourceLayout), Boolean.TRUE.equals(aiLayout));
    }

    static LayoutOptions defaults() { return new LayoutOptions("letter", 10, new BigDecimal("25.4"), "", "", ""); }
    static LayoutOptions uploadDefaults() { return new LayoutOptions("letter", BigDecimal.TEN, new BigDecimal("25.4"), "", "", "", true, true); }
    private static String text(String value, int limit) {
        if (value == null || value.length() > limit || value.codePoints().anyMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.SURROGATE)) throw invalid();
        return value.strip();
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,
            "Choose A4 or Letter, 10/11/12 pt, and margins from 10 to 50 mm (one decimal). Title/author allow 200 characters and date 80, without control characters."); }

    static LayoutOptions parse(String json) {
        if (json == null) return uploadDefaults();
        if (json.length() > 8192) throw invalid();
        try { var options = JSON.readValue(json, LayoutOptions.class); if (options == null) throw invalid(); return options; }
        catch (RuntimeException exception) { throw invalid(); }
    }

    static LayoutOptions read(Properties properties) {
        var fallback = defaults();
        try { return new LayoutOptions(properties.getProperty("layout.paper", fallback.paper()),
                new BigDecimal(properties.getProperty("layout.fontSize", fallback.fontSize().toPlainString())),
                new BigDecimal(properties.getProperty("layout.marginMm", fallback.marginMm().toPlainString())),
                properties.getProperty("layout.title", ""), properties.getProperty("layout.author", ""), properties.getProperty("layout.date", ""),
                flag(properties, "preserveSourceLayout"), flag(properties, "aiLayout")); }
        catch (RuntimeException exception) {
            LOG.warn("Invalid persisted layout settings; using legacy defaults. Saved source and history are unchanged.");
            return fallback;
        }
    }

    private static boolean flag(Properties properties, String name) {
        String value = properties.getProperty("layout." + name, "false");
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid persisted layout flag.");
        return Boolean.parseBoolean(value);
    }

    void write(Properties properties) {
        properties.setProperty("layout.paper", paper); properties.setProperty("layout.fontSize", fontSize.toPlainString());
        properties.setProperty("layout.marginMm", marginMm.toPlainString()); properties.setProperty("layout.title", title);
        properties.setProperty("layout.author", author); properties.setProperty("layout.date", date);
        properties.setProperty("layout.preserveSourceLayout", Boolean.toString(preserveSourceLayout));
        properties.setProperty("layout.aiLayout", Boolean.toString(aiLayout));
    }

    void prepare(Path output, List<String> command, SourceLayout sourceLayout) throws IOException {
        Files.write(output.resolve("layout-settings.json"), JSON.writeValueAsBytes(this));
        try (var input = new ClassPathResource("layout-settings.lua").getInputStream()) {
            Files.copy(input, output.resolve("layout-settings.lua"), StandardCopyOption.REPLACE_EXISTING);
        }
        command.add("--variable=papersize:" + paper); command.add("--variable=fontsize:" + fontSize.toPlainString() + "pt");
        command.add("--variable=geometry:" + (sourceLayout == null || sourceLayout.sections().isEmpty()
                ? "margin=" + marginMm.toPlainString() + "mm" : sourceLayout.sections().getFirst().geometry()));
        command.add("--lua-filter=layout-settings.lua");
    }

    String metadataText() { return title + " " + author + " " + date; }
    String description() {
        if (preserveSourceLayout) return "Original page size and margins when supported, " + fontSize.toPlainString()
                + " pt; fallback: " + paper.toUpperCase(java.util.Locale.ROOT) + ", " + marginMm.toPlainString() + " mm margins";
        return paper.toUpperCase(java.util.Locale.ROOT) + ", " + fontSize.toPlainString() + " pt, " + marginMm.toPlainString() + " mm margins";
    }
}
