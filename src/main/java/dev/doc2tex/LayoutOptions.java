package dev.doc2tex;

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
public record LayoutOptions(String paper, BigDecimal fontSize, BigDecimal marginMm, String title, String author, String date) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public LayoutOptions {
        if (!List.of("a4", "letter").contains(paper == null ? "" : paper) || fontSize == null
                || (fontSize.compareTo(BigDecimal.TEN) != 0 && fontSize.compareTo(BigDecimal.valueOf(11)) != 0 && fontSize.compareTo(BigDecimal.valueOf(12)) != 0)
                || marginMm == null || marginMm.compareTo(BigDecimal.TEN) < 0 || marginMm.compareTo(BigDecimal.valueOf(50)) > 0
                || marginMm.stripTrailingZeros().scale() > 1) throw invalid();
        fontSize = fontSize.stripTrailingZeros(); marginMm = marginMm.stripTrailingZeros();
        title = text(title, 200); author = text(author, 200); date = text(date, 80);
    }

    LayoutOptions(String paper, int fontSize, BigDecimal marginMm, String title, String author, String date) {
        this(paper, BigDecimal.valueOf(fontSize), marginMm, title, author, date);
    }

    static LayoutOptions defaults() { return new LayoutOptions("letter", 10, new BigDecimal("25.4"), "", "", ""); }
    private static String text(String value, int limit) {
        if (value == null || value.length() > limit || value.codePoints().anyMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.SURROGATE)) throw invalid();
        return value.strip();
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST,
            "Choose A4 or Letter, 10/11/12 pt, and margins from 10 to 50 mm (one decimal). Title/author allow 200 characters and date 80, without control characters."); }

    static LayoutOptions parse(String json) {
        if (json == null) return defaults();
        if (json.length() > 8192) throw invalid();
        try { var options = JSON.readValue(json, LayoutOptions.class); if (options == null) throw invalid(); return options; }
        catch (RuntimeException exception) { throw invalid(); }
    }

    static LayoutOptions read(Properties properties) {
        var fallback = defaults();
        return new LayoutOptions(properties.getProperty("layout.paper", fallback.paper()),
                new BigDecimal(properties.getProperty("layout.fontSize", fallback.fontSize().toPlainString())),
                new BigDecimal(properties.getProperty("layout.marginMm", fallback.marginMm().toPlainString())),
                properties.getProperty("layout.title", ""), properties.getProperty("layout.author", ""), properties.getProperty("layout.date", ""));
    }

    void write(Properties properties) {
        properties.setProperty("layout.paper", paper); properties.setProperty("layout.fontSize", fontSize.toPlainString());
        properties.setProperty("layout.marginMm", marginMm.toPlainString()); properties.setProperty("layout.title", title);
        properties.setProperty("layout.author", author); properties.setProperty("layout.date", date);
    }

    void prepare(Path output, List<String> command) throws IOException {
        Files.write(output.resolve("layout-settings.json"), JSON.writeValueAsBytes(this));
        try (var input = new ClassPathResource("layout-settings.lua").getInputStream()) {
            Files.copy(input, output.resolve("layout-settings.lua"), StandardCopyOption.REPLACE_EXISTING);
        }
        command.add("--variable=papersize:" + paper); command.add("--variable=fontsize:" + fontSize.toPlainString() + "pt");
        command.add("--variable=geometry:margin=" + marginMm.toPlainString() + "mm");
        command.add("--lua-filter=layout-settings.lua");
    }

    String metadataText() { return title + " " + author + " " + date; }
    String description() { return paper.toUpperCase(java.util.Locale.ROOT) + ", " + fontSize.toPlainString() + " pt, " + marginMm.toPlainString() + " mm margins"; }
}
