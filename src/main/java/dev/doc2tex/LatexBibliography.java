package dev.doc2tex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Literal TeX structure only; macro expansion/custom bibliography ownership stays manual. */
final class LatexBibliography {
    record Change(int line, String title, String before, String after) {}
    record Plan(int revision, String sourceHash, List<Change> changes, List<String> warnings) {}
    record Inspection(List<String> unknownKeys, List<String> warnings, int begin, int end, boolean existing, boolean verified) {}
    private record Group(int end, String value) {}
    private static final Set<String> CITATIONS = Set.of("cite", "citep", "citet", "citeauthor", "citeyear", "nocite", "autocite", "parencite", "textcite");
    private static final Set<String> LITERAL = Set.of("verbatim", "verbatim*", "Verbatim", "lstlisting", "minted");

    static Inspection inspect(String source, Set<String> known) {
        var unknown = new LinkedHashSet<String>(); var warnings = new LinkedHashSet<String>(); var environments = new ArrayDeque<String>();
        int index = 0, depth = 0, begin = -1, endDocument = -1, citationKeys = 0; boolean existing = false, verified = true;
        while (index < source.length()) {
            char value = source.charAt(index);
            if (value == '%') { index = lineEnd(source, index); continue; }
            if (value == '{') { if (++depth > 128) { verified = false; break; } index++; continue; }
            if (value == '}') { if (--depth < 0) { verified = false; break; } index++; continue; }
            if (value == '$') {
                String delimiter = source.startsWith("$$", index) ? "$$" : "$"; int close = mathEnd(source, delimiter, index + delimiter.length());
                if (close < 0) { verified = false; break; } index = close + delimiter.length(); continue;
            }
            if (value != '\\') { index++; continue; }
            int start = index++, end = index; if (index == source.length()) break;
            while (end < source.length() && Character.isLetter(source.charAt(end))) end++; if (end == index) end++;
            String command = source.substring(index, end); index = end;
            if (command.equals("verb")) {
                if (index < source.length() && source.charAt(index) == '*') index++;
                if (index >= source.length() || Character.isWhitespace(source.charAt(index))) { verified = false; break; }
                char delimiter = source.charAt(index++); int close = source.indexOf(delimiter, index);
                if (close < 0 || source.substring(index, close).contains("\n")) { verified = false; break; } index = close + 1; continue;
            }
            if (command.equals("(") || command.equals("[")) {
                String close = command.equals("(") ? "\\)" : "\\]"; int position = source.indexOf(close, index);
                if (position < 0) { verified = false; break; } index = position + 2; continue;
            }
            if (Set.of("bibliography", "bibliographystyle", "addbibresource", "printbibliography").contains(command)) existing = true;
            if (command.equals("usepackage") || command.equals("RequirePackage")) {
                int position = space(source, index);
                if (position < source.length() && source.charAt(position) == '[') { Group options = group(source, position, '[', ']'); if (options == null) { verified = false; break; } position = space(source, options.end()); }
                Group packages = group(source, position, '{', '}'); if (packages == null) { verified = false; break; } index = packages.end();
                for (String name : packages.value().split(",")) if (Set.of("biblatex", "natbib", "citeproc").contains(name.strip())) {
                    existing = true; warnings.add("A custom bibliography package is present; the bundled plain BibTeX setup may require manual settings.");
                }
                continue;
            }
            if (command.equals("begin") || command.equals("end")) {
                Group environment = group(source, space(source, index), '{', '}'); if (environment == null) { verified = false; break; }
                index = environment.end(); String name = environment.value();
                if (name.equals("thebibliography")) existing = true;
                if (depth != 0) continue;
                if (command.equals("begin") && LITERAL.contains(name)) {
                    String close = "\\end{" + name + "}"; int position = source.indexOf(close, index);
                    if (position < 0) { verified = false; break; } index = position + close.length(); continue;
                }
                if (command.equals("begin")) {
                    if (environments.size() >= 128) { verified = false; break; } environments.push(name);
                    if (name.equals("document")) { if (begin >= 0) { verified = false; break; } begin = start; }
                } else {
                    if (environments.isEmpty() || !environments.pop().equals(name)) { verified = false; break; }
                    if (name.equals("document")) { if (endDocument >= 0) { verified = false; break; } endDocument = start; }
                }
                continue;
            }
            if (!CITATIONS.contains(command)) continue;
            if (index < source.length() && source.charAt(index) == '*') index++; index = space(source, index);
            for (int note = 0; note < 2 && index < source.length() && source.charAt(index) == '['; note++) {
                Group group = group(source, index, '[', ']'); if (group == null) { verified = false; break; } index = space(source, group.end());
            }
            if (!verified) break;
            Group keys = group(source, index, '{', '}'); if (keys == null) { warnings.add("A custom citation command needs manual key review."); continue; }
            index = keys.end();
            if (depth != 0) warnings.add("Nested citation commands may need manual key review.");
            String literal = keys.value().replaceAll("%[^\n]*(?:\n|$)", "");
            for (String key : literal.split(",", 2001)) {
                if (++citationKeys > 2000) { warnings.add("Only the first 2,000 citation key occurrences were inspected; compare compiler warnings for later citations."); break; }
                key = key.strip(); if (command.equals("nocite") && key.equals("*")) continue;
                if (!BibtexLibrary.validKey(key)) { warnings.add("Dynamic or unsupported citation keys require manual review."); continue; }
                if (!known.contains(key) && unknown.size() < 200) unknown.add(key);
            }
        }
        verified &= depth == 0 && environments.isEmpty() && begin >= 0 && endDocument > begin;
        if (!verified) warnings.add("Document structure could not be verified; bibliography setup requires manual source editing.");
        return new Inspection(List.copyOf(unknown), warnings.stream().limit(32).toList(), begin, endDocument, existing, verified);
    }

    static Plan plan(String source, int revision, BibliographyAssets assets) {
        var inspection = inspect(source, Set.of()); var warnings = new ArrayList<>(inspection.warnings());
        String hash = SourceVersionStore.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (assets == null) { warnings.add("Import a bibliography before adding its source section."); return new Plan(revision, hash, List.of(), warnings); }
        if (inspection.existing()) { warnings.add("The source already contains bibliography commands. Review that setup manually; no replacement is proposed."); return new Plan(revision, hash, List.of(), warnings); }
        if (!inspection.verified()) return new Plan(revision, hash, List.of(), warnings);
        if (!source.startsWith("\\begin{document}", inspection.begin()) || !source.startsWith("\\end{document}", inspection.end())) {
            warnings.add("Custom document markers require manual bibliography setup."); return new Plan(revision, hash, List.of(), warnings);
        }
        String header = assets.font() == null ? "" : "\\usepackage{fontspec}\n";
        String section = "\n% doc2TeX bibliography: standard BibTeX, included in the saved version\n\\begingroup\n"
                + (assets.font() == null ? "" : "\\fontspec[Path=bibliography/]{NotoSerif-Regular.ttf}\n")
                + "\\bibliographystyle{plain}\n\\bibliography{bibliography/references}\n\\endgroup\n";
        var changes = new ArrayList<Change>();
        if (!header.isEmpty()) changes.add(new Change(line(source, inspection.begin()), "Load the Unicode bibliography font", "\\begin{document}", header + "\\begin{document}"));
        changes.add(new Change(line(source, inspection.end()), "Add the bibliography section", "\\end{document}", section + "\\end{document}"));
        if (assets.font() == null) warnings.add("The bundled bibliography font is unavailable. Install project fonts or choose a font covering your bibliography's scripts before compiling.");
        warnings.add("The plain BibTeX setup uses Tectonic or XeLaTeX + BibTeX. Unusual scripts and custom bibliography packages may require manual settings.");
        return new Plan(revision, hash, List.copyOf(changes), List.copyOf(warnings));
    }

    static String apply(String source, int revision, String hash, BibliographyAssets assets) {
        Plan plan = plan(source, revision, assets);
        if (!plan.sourceHash().equals(hash)) throw new ApiException(HttpStatus.CONFLICT, "The source changed. Preview bibliography setup again.");
        if (plan.changes().isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "No safe bibliography setup is available. Review the existing source manually.");
        var inspection = inspect(source, Set.of()); StringBuilder result = new StringBuilder(source);
        Change tail = plan.changes().getLast(); result.replace(inspection.end(), inspection.end() + tail.before().length(), tail.after());
        if (plan.changes().size() == 2) { Change header = plan.changes().getFirst(); result.replace(inspection.begin(), inspection.begin() + header.before().length(), header.after()); }
        return result.toString();
    }

    private static int line(String text, int index) { return 1 + (int) text.substring(0, index).chars().filter(value -> value == '\n').count(); }
    private static int space(String text, int index) { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; return index; }
    private static int lineEnd(String text, int index) { int end = text.indexOf('\n', index); return end < 0 ? text.length() : end + 1; }
    private static int mathEnd(String text, String delimiter, int index) {
        while (index < text.length()) { if (text.charAt(index) == '\\') index += 2; else if (text.charAt(index) == '%') index = lineEnd(text, index); else if (text.startsWith(delimiter, index)) return index; else index++; } return -1;
    }
    private static Group group(String text, int index, char open, char close) {
        if (index >= text.length() || text.charAt(index) != open) return null;
        int start = index + 1, depth = 1, braces = 0;
        for (int cursor = start; cursor < text.length(); cursor++) {
            char next = text.charAt(cursor);
            if (next == '\\') { cursor++; continue; }
            if (next == '%') { cursor = lineEnd(text, cursor) - 1; continue; }
            if (open == '[') { if (next == '{') braces++; else if (next == '}' && --braces < 0) return null; if (braces > 128) return null; if (braces > 0) continue; }
            if (next == open && ++depth > 128) return null;
            if (next == close && --depth == 0) return new Group(cursor + 1, text.substring(start, cursor));
        }
        return null;
    }
}
