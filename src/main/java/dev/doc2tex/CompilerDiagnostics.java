package dev.doc2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Bounded compiler output snapshots; locations are display data, never filesystem links. */
final class CompilerDiagnostics {
    static final int LOG_LIMIT = 64 * 1024;
    private static final int ITEM_LIMIT = 200;
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern LOCATION = Pattern.compile("^(?:error:|warning:)?\\s*(.+?\\.(?:tex|sty|cls)):(\\d+):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE = Pattern.compile("^(?:error:|warning:|!|.*(?:LaTeX|Package .+?) (?:Warning|Error):).*", Pattern.CASE_INSENSITIVE);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Diagnostic(String severity, String code, String title, String explanation, String file, Integer line, String raw) {}
    record Report(Integer revision, String status, boolean logTruncated, boolean diagnosticsTruncated, List<Diagnostic> items, String rawLog) {}

    static Report fromLog(Path log, Integer revision, String status, String failure) throws IOException {
        JobStore.checkUnlinked(log);
        byte[] bytes;
        if (!Files.isRegularFile(log)) bytes = new byte[0];
        else try (var input = Files.newInputStream(log)) { bytes = input.readNBytes(LOG_LIMIT + 1); }
        boolean truncated = bytes.length > LOG_LIMIT;
        String text = new String(bytes, 0, Math.min(bytes.length, LOG_LIMIT), StandardCharsets.UTF_8);
        return parse(text, revision, status, truncated, failure);
    }

    static Report parse(String text, Integer revision, String status, boolean truncated, String failure) {
        String clean = ANSI.matcher(text).replaceAll("").replace("\r\n", "\n").replace('\r', '\n');
        List<Diagnostic> items = new ArrayList<>();
        var seen = new LinkedHashSet<String>();
        String[] lines = clean.split("\n", -1);
        boolean omitted = false;
        for (int index = 0; index < lines.length; index++) {
            String first = lines[index].strip();
            var location = LOCATION.matcher(first);
            boolean located = location.matches();
            String lower = first.toLowerCase(Locale.ROOT);
            boolean recognized = lower.contains("missing character:") || lower.contains("overfull \\")
                    || lower.contains("does not contain the requested") || lower.contains("fontconfig error:");
            if (!located && !recognized && !MESSAGE.matcher(first).matches()) continue;
            String file = located ? location.group(1) : null;
            Integer line = null;
            if (located) try { int number = Integer.parseInt(location.group(2)); if (number > 0) line = number; }
                catch (NumberFormatException ignored) { /* An invalid location remains visible without navigation. */ }
            StringBuilder raw = new StringBuilder(first);
            // Keep nearby multiline context, bounded independently of the total log.
            for (int next = index + 1; next < Math.min(lines.length, index + 7); next++) {
                String continuation = lines[next].strip();
                if (continuation.isEmpty() || LOCATION.matcher(continuation).matches() || MESSAGE.matcher(continuation).matches()) break;
                if (raw.length() + continuation.length() > 2048) break;
                raw.append('\n').append(continuation);
            }
            String message = raw.toString();
            String content = message.toLowerCase(Locale.ROOT);
            String severity = lower.startsWith("error:") || first.startsWith("!") || lower.contains(" error:") ? "error" : "warning";
            String code = "compiler-message", title = "Compiler message", explanation = "Read the compiler output and nearby source. This message has no suggested automatic repair.";
            if (content.contains("undefined control sequence")) {
                code = "undefined-command"; title = "Unknown LaTeX command";
                explanation = "Check the command spelling and whether its required package is loaded.";
            } else if (content.contains("fontconfig error:")) {
                severity = "warning"; code = "font-configuration"; title = "Font configuration warning";
                explanation = "The system font configuration reported a problem. Check the PDF fonts; compilation may still succeed.";
            } else if ((content.contains("fontspec") || content.contains("the font ") || content.contains(": font ") || lower.startsWith("! font ") || lower.startsWith("font ") || content.contains("font-not-found"))
                    && (content.contains("not found") || content.contains("cannot be found") || content.contains("font-not-found"))) {
                code = "missing-font"; title = "Font unavailable";
                explanation = "Check that the named font is available and that the source uses the intended font name or bundled font path.";
            } else if (content.contains("unable to load picture") || content.contains("file") && (content.contains("not found") || content.contains("cannot find") || content.contains("can't find"))) {
                code = "missing-file"; title = "File or image unavailable";
                explanation = "Check the asset filename and relative path in the source and ensure the file is included in this project.";
            } else if (content.contains("missing character:") || content.contains("does not contain the requested")) {
                severity = "warning"; code = "missing-glyph"; title = "Font is missing a character";
                explanation = "Some text may be invisible. Choose a font that contains the reported character and check the PDF.";
            } else if (content.contains("overfull \\")) {
                severity = "warning"; code = "overfull-box"; title = "Content exceeds its box";
                explanation = "Check wide tables, equations or long lines in the PDF and adjust the layout or line breaks.";
            }
            String key = severity + "|" + code + "|" + file + "|" + line + "|" + first;
            if (!seen.add(key)) continue;
            if (items.size() == ITEM_LIMIT) { omitted = true; continue; }
            items.add(new Diagnostic(severity, code, title, explanation, file, line, message));
        }
        if (status.equals("FAILED") && items.stream().noneMatch(item -> item.severity().equals("error"))) {
            if (items.size() == ITEM_LIMIT) { items.removeLast(); omitted = true; }
            items.add(new Diagnostic("error", "build-failed", "PDF build failed", "Open the raw compiler log for details. The compiler did not provide a usable source location.", null, null,
                    failure == null || failure.isBlank() ? "The compiler did not produce a PDF." : failure));
        }
        return new Report(revision, status, truncated, omitted, List.copyOf(items), text);
    }

    static void save(Path directory, Report report) throws IOException {
        Path destination = directory.resolve("compiler-diagnostics.json"), temporary = directory.resolve("compiler-diagnostics.json.tmp");
        JobStore.checkUnlinked(destination); JobStore.checkUnlinked(temporary);
        Files.writeString(temporary, JSON.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Report load(Path directory) throws IOException {
        Path file = directory.resolve("compiler-diagnostics.json"); JobStore.checkUnlinked(file);
        if (!Files.isRegularFile(file)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("Compiler diagnostics snapshot is too large.");
            Report report = JSON.readValue(bytes, Report.class);
            if (report == null || report.revision() == null || report.revision() < 0 || report.status() == null || !List.of("READY", "FAILED").contains(report.status())
                    || report.rawLog() == null || report.items() == null || report.items().size() > ITEM_LIMIT)
                throw new IOException("Compiler diagnostics metadata is incomplete.");
            for (var item : report.items()) if (item == null || item.severity() == null || !List.of("error", "warning").contains(item.severity())
                    || item.code() == null || item.title() == null || item.explanation() == null || item.raw() == null)
                throw new IOException("Compiler diagnostic metadata is incomplete.");
            return report;
        }
    }
}
