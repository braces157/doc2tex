package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Generates verified, Tectonic-compatible academic LaTeX templates with literal text escaping. */
@Service
class AcademicTemplateService {
    record TemplateDescriptor(
            String id,
            String name,
            String category,
            String description,
            String defaultPaper,
            int defaultFontSize,
            boolean twoColumn) {}

    record TemplateRequest(
            String templateId,
            String title,
            String author,
            String affiliation,
            String abstractText,
            String date,
            String paper,
            Integer fontSize) {}

    record GeneratedTemplate(String templateId, String filename, String source) {}
    record CompiledTemplate(String filename, byte[] bytes) {}

    private static final List<TemplateDescriptor> CATALOG = List.of(
            new TemplateDescriptor("ieee-twocol", "IEEE-Style Conference (2-col)", "Conference",
                    "Two-column engineering paper with abstract, keywords, sectioning, and equation/figure placeholders.",
                    "letter", 10, true),
            new TemplateDescriptor("acm-manuscript", "ACM-Style Manuscript", "Journal",
                    "Single-column technical manuscript with generous margins, author affiliation block, and CCS/keywords section.",
                    "letter", 11, false),
            new TemplateDescriptor("lncs-paper", "LNCS-Style Proceedings", "Conference",
                    "Clean A4 proceedings layout with centered title block, institute metadata, and numbered theorems.",
                    "a4", 10, false),
            new TemplateDescriptor("beamer-slides", "Beamer Presentation Slides", "Slides",
                    "Clean 16:9 widescreen slide deck ready for lectures, seminars, and project defenses.",
                    "a4", 11, false),
            new TemplateDescriptor("thesis-book", "Thesis & Dissertation", "Long-form",
                    "Multi-chapter book class with title page, table of contents, abstract, and chapter structure.",
                    "a4", 11, false),
            new TemplateDescriptor("academic-cv", "Academic CV & Resume", "Career",
                    "Crisp section-ruled curriculum vitae for publications, appointments, grants, and teaching.",
                    "letter", 10, false));

    private final Tools tools;
    private final ToolRunner runner;
    private final AppProperties properties;

    AcademicTemplateService(Tools tools, ToolRunner runner, AppProperties properties) {
        this.tools = tools;
        this.runner = runner;
        this.properties = properties;
    }

    List<TemplateDescriptor> list() {
        return CATALOG;
    }

    GeneratedTemplate generate(TemplateRequest request) {
        if (request == null) throw bad("Provide template options.");
        TemplateDescriptor desc = CATALOG.stream()
                .filter(item -> item.id().equals(request.templateId()))
                .findFirst()
                .orElseThrow(() -> bad("Unsupported template \"" + request.templateId() + "\"."));

        String title = safeText(request.title(), 200, "Untitled Document");
        String author = safeText(request.author(), 200, "Author Name");
        String affiliation = safeText(request.affiliation(), 200, "Department of Computer Science");
        String abs = safeText(request.abstractText(), 2000,
                "This draft outlines the problem scope, methodology, and measured results.", false, true);
        String date = safeText(request.date(), 80, "\\today", true, false);
        String paper = normalizePaper(request.paper(), desc.defaultPaper());
        int fontSize = normalizeFontSize(request.fontSize(), desc.defaultFontSize());

        String texTitle = escapeLatex(title);
        String texAuthor = escapeLatex(author);
        String texAffil = escapeLatex(affiliation);
        String texAbstract = escapeLatex(abs);
        String texDate = date.equals("\\today") ? "\\today" : escapeLatex(date);

        String source = switch (desc.id()) {
            case "ieee-twocol" -> buildIeeeTwoCol(paper, fontSize, texTitle, texAuthor, texAffil, texAbstract, texDate);
            case "acm-manuscript" -> buildAcmManuscript(paper, fontSize, texTitle, texAuthor, texAffil, texAbstract, texDate);
            case "lncs-paper" -> buildLncsPaper(paper, fontSize, texTitle, texAuthor, texAffil, texAbstract, texDate);
            case "beamer-slides" -> buildBeamerSlides(texTitle, texAuthor, texAffil, texDate);
            case "thesis-book" -> buildThesisBook(paper, fontSize, texTitle, texAuthor, texAffil, texAbstract, texDate);
            case "academic-cv" -> buildAcademicCv(paper, fontSize, texTitle, texAuthor, texAffil);
            default -> throw bad("Unsupported template.");
        };
        return new GeneratedTemplate(desc.id(), desc.id() + ".tex", source);
    }

    CompiledTemplate compilePdf(TemplateRequest request) throws IOException {
        if (!tools.available(tools.tectonic())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Tectonic is unavailable. Install tools before compiling templates.");
        }
        GeneratedTemplate generated = generate(request);
        Path tempDir = Files.createTempDirectory("doc2tex-tpl-");
        try {
            Path tex = tempDir.resolve("main.tex");
            Path outDir = tempDir.resolve("out");
            Files.createDirectories(outDir);
            Files.writeString(tex, generated.source(), StandardCharsets.UTF_8);
            try {
                ToolRunner.Result result = runner.run(
                        List.of(tools.tectonic(), "--untrusted", "--color", "never", "--outdir", outDir.toString(), "main.tex"),
                        tempDir,
                        tempDir.resolve("compile.log"),
                        properties.previewTimeoutSeconds());
                Path pdf = outDir.resolve("main.pdf");
                if (result.exitCode() != 0 || !Files.isRegularFile(pdf)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Template compilation failed: " + PreviewFailure.message(result.log()));
                }
                return new CompiledTemplate(generated.templateId() + ".pdf", Files.readAllBytes(pdf));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Template compilation was interrupted.", exception);
            }
        } finally {
            SourceVersionStore.removeTreeQuietly(tempDir);
        }
    }

    private static String buildIeeeTwoCol(
            String paper, int fontSize, String title, String author, String affil, String abs, String date) {
        return """
                \\documentclass[%dpt,%spaper,twocolumn]{article}
                \\usepackage[margin=18mm]{geometry}
                \\usepackage{amsmath,amssymb,booktabs,graphicx,hyperref}
                \\setlength{\\columnsep}{7mm}
                \\title{\\textbf{%s}}
                \\author{%s \\\\ \\small %s}
                \\date{%s}
                \\begin{document}
                \\maketitle
                \\begin{abstract}
                %s
                \\end{abstract}
                \\textbf{Keywords:} reproducibility, local publishing, document engineering.
                \\section{Introduction}
                Introduce the research problem, motivation, and key contributions here.
                \\section{Methodology}
                \\begin{equation}
                E = mc^2
                \\end{equation}
                \\section{Results and Discussion}
                \\begin{table}[h]
                \\centering
                \\caption{Baseline comparison.}
                \\begin{tabular}{@{}lcc@{}}
                \\toprule
                Method & Accuracy & Latency (ms) \\\\
                \\midrule
                Baseline & 0.91 & 14.2 \\\\
                Proposed & 0.96 & 9.8 \\\\
                \\bottomrule
                \\end{tabular}
                \\end{table}
                \\section{Conclusion}
                Summarize key takeaways and future directions.
                \\end{document}
                """.formatted(fontSize, paper, title, author, affil, date, abs);
    }

    private static String buildAcmManuscript(
            String paper, int fontSize, String title, String author, String affil, String abs, String date) {
        return """
                \\documentclass[%dpt,%spaper]{article}
                \\usepackage[margin=25mm]{geometry}
                \\usepackage{amsmath,booktabs,graphicx,hyperref}
                \\title{%s}
                \\author{%s \\\\ \\textit{%s}}
                \\date{%s}
                \\begin{document}
                \\maketitle
                \\begin{abstract}
                %s
                \\end{abstract}
                \\noindent\\textbf{CCS Concepts:} Human-centered computing $\\rightarrow$ Document preparation.
                \\section{Introduction}
                Frame the research question, artifact evaluation criteria, and empirical context.
                \\section{System Design}
                Describe architectural components, data flows, and failure bounds.
                \\section{Evaluation}
                Present quantitative benchmarks and qualitative findings.
                \\end{document}
                """.formatted(fontSize, paper, title, author, affil, date, abs);
    }

    private static String buildLncsPaper(
            String paper, int fontSize, String title, String author, String affil, String abs, String date) {
        return """
                \\documentclass[%dpt,%spaper]{article}
                \\usepackage[margin=28mm]{geometry}
                \\usepackage{amsmath,amsthm,booktabs,hyperref}
                \\newtheorem{theorem}{Theorem}
                \\title{\\Large\\bfseries %s}
                \\author{%s \\\\ \\small %s}
                \\date{%s}
                \\begin{document}
                \\maketitle
                \\begin{abstract}
                %s
                \\end{abstract}
                \\section{Preliminaries}
                State definitions and notation clearly.
                \\begin{theorem}
                Every bounded conversion preserves deterministic provenance.
                \\end{theorem}
                \\section{Conclusion}
                Conclude with open questions.
                \\end{document}
                """.formatted(fontSize, paper, title, author, affil, date, abs);
    }

    private static String buildBeamerSlides(String title, String author, String affil, String date) {
        return """
                \\documentclass[aspectratio=169]{beamer}
                \\usetheme{default}
                \\usecolortheme{beaver}
                \\title{%s}
                \\author{%s}
                \\institute{%s}
                \\date{%s}
                \\begin{document}
                \\begin{frame}
                \\titlepage
                \\end{frame}
                \\begin{frame}{Agenda}
                \\begin{itemize}
                  \\item Problem statement and constraints
                  \\item System architecture
                  \\item Measured verification results
                \\end{itemize}
                \\end{frame}
                \\begin{frame}{Key Findings}
                Summarize experimental takeaways and next milestones cleanly.
                \\end{frame}
                \\end{document}
                """.formatted(title, author, affil, date);
    }

    private static String buildThesisBook(
            String paper, int fontSize, String title, String author, String affil, String abs, String date) {
        return """
                \\documentclass[%dpt,%spaper,oneside]{book}
                \\usepackage[margin=25mm]{geometry}
                \\usepackage{amsmath,booktabs,graphicx,hyperref}
                \\title{\\Huge\\bfseries %s}
                \\author{%s \\\\ %s}
                \\date{%s}
                \\begin{document}
                \\frontmatter
                \\maketitle
                \\chapter*{Abstract}
                %s
                \\tableofcontents
                \\mainmatter
                \\chapter{Introduction}
                Establish the thesis scope, prior work, and chapter roadmap.
                \\chapter{Architecture and Implementation}
                Document core models, interfaces, and safety invariants.
                \\chapter{Evaluation and Conclusions}
                Report empirical measurements and remaining limitations.
                \\end{document}
                """.formatted(fontSize, paper, title, author, affil, date, abs);
    }

    private static String buildAcademicCv(String paper, int fontSize, String title, String author, String affil) {
        return """
                \\documentclass[%dpt,%spaper]{article}
                \\usepackage[margin=18mm]{geometry}
                \\usepackage{enumitem,hyperref}
                \\setlength{\\parindent}{0pt}
                \\begin{document}
                {\\LARGE\\bfseries %s} \\hfill {\\small %s}\\\\[4pt]
                {\\small %s}
                \\vspace{8pt}\\hrule\\vspace{10pt}
                \\section*{Education}
                \\begin{itemize}[leftmargin=1.4em]
                  \\item Ph.D. in Computer Science, University Name (2022--2026)
                \\end{itemize}
                \\section*{Selected Publications}
                \\begin{itemize}[leftmargin=1.4em]
                  \\item %s, ``Title of Paper,'' \\textit{Proceedings of Conference}, 2026.
                \\end{itemize}
                \\section*{Teaching \\& Service}
                \\begin{itemize}[leftmargin=1.4em]
                  \\item Program Committee Member and Graduate Teaching Assistant.
                \\end{itemize}
                \\end{document}
                """.formatted(fontSize, paper, author, title, affil, author);
    }

    private static String safeText(String value, int maxLen, String fallback) {
        return safeText(value, maxLen, fallback, false, false);
    }

    private static String safeText(String value, int maxLen, String fallback, boolean allowToday, boolean allowNewlines) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = allowNewlines ? value.replace("\r\n", "\n").replace('\r', '\n').strip() : value.strip();
        boolean hasBadControl = normalized.codePoints().anyMatch(cp ->
                Character.isISOControl(cp) && (!allowNewlines || (cp != '\n' && cp != '\t')));
        if (normalized.length() > maxLen || hasBadControl) {
            throw bad("Template text fields cannot exceed " + maxLen + " characters or contain control characters.");
        }
        if (allowToday && normalized.equals("\\today")) return "\\today";
        return normalized;
    }

    private static String normalizePaper(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String paper = raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("a4", "letter").contains(paper)) throw bad("Paper size must be a4 or letter.");
        return paper;
    }

    private static int normalizeFontSize(Integer raw, int fallback) {
        if (raw == null) return fallback;
        if (!List.of(10, 11, 12).contains(raw)) throw bad("Font size must be 10, 11, or 12 pt.");
        return raw;
    }

    static String escapeLatex(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\textbackslash{}");
                case '{' -> out.append("\\{");
                case '}' -> out.append("\\}");
                case '$' -> out.append("\\$");
                case '%' -> out.append("\\%");
                case '&' -> out.append("\\&");
                case '#' -> out.append("\\#");
                case '_' -> out.append("\\_");
                case '^' -> out.append("\\textasciicircum{}");
                case '~' -> out.append("\\textasciitilde{}");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
