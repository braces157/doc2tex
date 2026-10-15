package dev.doc2tex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class FontSupport {
    private final Path fonts = Path.of(System.getenv().getOrDefault("DOC2TEX_FONT_DIR", ".tools/fonts")).toAbsolutePath();

    void prepare(DocumentInspector.Review review, Path output, List<String> command) throws IOException {
        prepare(review, output, command, "");
    }

    void prepare(DocumentInspector.Review review, Path output, List<String> command, String metadata) throws IOException {
        var scripts = new java.util.LinkedHashSet<>(review.scripts()); DocumentInspector.detectScripts(metadata, scripts);
        review = new DocumentInspector.Review(review.elements(), review.findings(), review.compilerWarnings(), List.copyOf(scripts), review.scope());
        if (review.scripts().isEmpty() || !Files.isRegularFile(fonts.resolve("NotoSerif-Regular.ttf"))) return;
        boolean thai = review.scripts().contains("thai");
        boolean arabic = review.scripts().contains("arabic");
        boolean cjk = review.scripts().stream().anyMatch(List.of("han", "hiragana", "katakana", "hangul")::contains);
        // Apply the filter only when every required font is available; otherwise keep plain Unicode.
        if ((thai && !Files.isRegularFile(fonts.resolve("NotoSansThai-Regular.ttf")))
                || (arabic && !Files.isRegularFile(fonts.resolve("NotoNaskhArabic-Regular.ttf")))
                || (cjk && !Files.isRegularFile(fonts.resolve("NotoSansCJKsc-Regular.otf")))) return;
        Path target = output.resolve("fonts");
        Files.createDirectories(target);
        copy("NotoSerif-Regular.ttf", target);
        copy("OFL-Noto.txt", target);
        // Load hyperref before polyglossia/bidi; Pandoc's default template loads it later.
        StringBuilder header = new StringBuilder("\\usepackage{bookmark}\n\\setmainfont{NotoSerif-Regular.ttf}[Path=fonts/]\n");
        if (thai) {
            copy("NotoSansThai-Regular.ttf", target);
            header.append("\\newfontfamily\\DocThaiFont[Script=Thai,Path=fonts/]{NotoSansThai-Regular.ttf}\n")
                    .append("\\newcommand{\\DocThai}[1]{{\\DocThaiFont\\XeTeXlinebreaklocale \"th\" #1}}\n");
        }
        if (cjk) {
            copy("NotoSansCJKsc-Regular.otf", target); copy("OFL-Noto-CJK.txt", target);
            header.append("\\newfontfamily\\DocCJKFont[Path=fonts/]{NotoSansCJKsc-Regular.otf}\n")
                    .append("\\newcommand{\\DocCJK}[1]{{\\DocCJKFont\\XeTeXlinebreaklocale \"zh\" #1}}\n");
        }
        if (arabic) {
            copy("NotoNaskhArabic-Regular.ttf", target);
            header.append("\\usepackage{polyglossia}\n\\setdefaultlanguage{english}\n\\setotherlanguage{arabic}\n")
                    .append("\\newfontfamily\\arabicfont[Script=Arabic,Path=fonts/]{NotoNaskhArabic-Regular.ttf}\n");
        }
        header.append("\\pdfstringdefDisableCommands{\\def\\DocThai#1{#1}\\def\\DocCJK#1{#1}\\def\\textarabic#1{#1}}\n");
        Files.writeString(output.resolve("unicode-fonts.tex"), header);
        try (var input = new ClassPathResource("unicode-fonts.lua").getInputStream()) {
            Files.copy(input, output.resolve("unicode-fonts.lua"), StandardCopyOption.REPLACE_EXISTING);
        }
        command.add("--include-in-header=unicode-fonts.tex");
        command.add("--lua-filter=unicode-fonts.lua");
    }

    private void copy(String filename, Path target) throws IOException {
        Files.copy(fonts.resolve(filename), target.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
    }
}
