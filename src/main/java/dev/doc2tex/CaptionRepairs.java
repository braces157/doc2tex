package dev.doc2tex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** A conservative literal-structure reader, not a TeX expansion engine. Never edits prose. */
final class CaptionRepairs {
    record Proposal(String id, String title, int line, int start, int end, String before, String after) {}
    record Plan(int revision, String sourceHash, List<Proposal> proposals, List<String> warnings) {}
    private record Group(int from, int to, String text) {}
    private record Prefix(int start, int end, String word, String number) {}
    private static final Set<String> OWNERS = Set.of("figure", "figure*", "table", "table*", "longtable");
    private static final Set<String> LITERAL = Set.of("verbatim", "verbatim*", "Verbatim", "lstlisting", "minted");
    private static final String IDENTITY = "\\providecommand{\\DocCaptionNumber}[3]{#1~#2#3}";
    private static final Pattern REDEFINED = Pattern.compile("\\\\(?:newcommand|renewcommand|def|gdef|edef|xdef|let)\\s*(?:\\{\\s*)?\\\\DocCaptionNumber\\b");
    private static final Pattern PLAIN = Pattern.compile("(?i)^(Figure|Fig\\.?|Table)\\s+([0-9]+(?:\\.[0-9]+)*)(?:[.:)]?)\\s+");

    static Plan preview(String source, int revision, List<String> referenceWarnings) {
        String hash = ReviewEvidence.digest(source); var proposals = new ArrayList<Proposal>(); var warnings = new LinkedHashSet<>(referenceWarnings);
        var environments = new ArrayDeque<String>(); int depth = 0; boolean uncertain = false;
        boolean identity = source.contains(IDENTITY) && !REDEFINED.matcher(source).find();
        for (int index = 0; index < source.length();) {
            char value = source.charAt(index);
            if (value == '%') { index = lineEnd(source, index); continue; }
            if (value == '$') {
                String delimiter = source.startsWith("$$", index) ? "$$" : "$";
                int end = literalEnd(source, delimiter, index + delimiter.length());
                if (end < 0) { uncertain = true; break; } index = end + delimiter.length(); continue;
            }
            if (value == '{') { if (++depth > 128) { uncertain = true; break; } index++; continue; }
            if (value == '}') { if (--depth < 0) { uncertain = true; break; } index++; continue; }
            if (value != '\\') { index++; continue; }
            int commandStart = index++, end = index;
            if (index >= source.length()) break;
            while (end < source.length() && Character.isLetter(source.charAt(end))) end++;
            if (end == index) end++;
            String command = source.substring(index, end); index = end;
            if (command.equals("verb")) {
                if (index < source.length() && source.charAt(index) == '*') index++;
                if (index >= source.length() || Character.isWhitespace(source.charAt(index))) { uncertain = true; break; }
                char delimiter = source.charAt(index++); int close = source.indexOf(delimiter, index);
                if (close < 0 || source.substring(index, close).contains("\n")) { uncertain = true; break; } index = close + 1; continue;
            }
            if (command.equals("(") || command.equals("[")) {
                String close = command.equals("(") ? "\\)" : "\\]"; int position = source.indexOf(close, index);
                if (position < 0) { uncertain = true; break; } index = position + close.length(); continue;
            }
            if ((command.equals("begin") || command.equals("end")) && depth == 0) {
                Group environment = group(source, skipSpace(source, index), '{', '}');
                if (environment == null) { uncertain = true; break; } index = environment.to(); String name = environment.text();
                if (command.equals("begin") && LITERAL.contains(name)) {
                    String close = "\\end{" + name + "}"; int position = source.indexOf(close, index);
                    if (position < 0) { uncertain = true; break; } index = position + close.length(); continue;
                }
                if (command.equals("begin")) { if (environments.size() >= 128) { uncertain = true; break; } environments.push(name); }
                else if (environments.isEmpty() || !environments.pop().equals(name)) { uncertain = true; break; }
                continue;
            }
            if (!command.equals("caption")) continue;
            boolean starred = index < source.length() && source.charAt(index) == '*'; if (starred) index++;
            index = skipSpace(source, index); Group shortCaption = null;
            if (index < source.length() && source.charAt(index) == '[') {
                shortCaption = group(source, index, '[', ']'); if (shortCaption == null) { uncertain = true; break; } index = skipSpace(source, shortCaption.to());
            }
            Group caption = group(source, index, '{', '}');
            if (caption == null) { uncertain = true; break; } index = caption.to();
            if (starred || depth != 0 || environments.isEmpty() || !OWNERS.contains(environments.peek())) continue;
            if (!source.substring(source.lastIndexOf('\n', commandStart) + 1, commandStart).isBlank()) {
                warnings.add("An inline or custom caption needs manual numbering review."); continue;
            }
            if (shortCaption != null) { warnings.add("A caption with optional short text requires manual numbering review."); continue; }
            String kind = environments.peek().startsWith("figure") ? "figure" : "table";
            Prefix prefix = prefix(caption, kind, identity);
            if (prefix == null) continue;
            String before = source.substring(commandStart, caption.to());
            int from = caption.from() + prefix.start(), to = caption.from() + prefix.end();
            String after = source.substring(commandStart, from) + source.substring(to, caption.to());
            String id = ReviewEvidence.digest(hash + "|" + commandStart + "|" + before);
            if (proposals.size() == 100) { warnings.add("Only the first 100 caption repairs are shown; apply and preview again for more."); continue; }
            proposals.add(new Proposal(id, "Remove repeated " + prefix.word() + " " + prefix.number() + " prefix", 1 + (int) source.substring(0, commandStart).chars().filter(character -> character == '\n').count(), commandStart, caption.to(), before, after));
        }
        if (uncertain || depth != 0 || !environments.isEmpty()) {
            proposals.clear(); warnings.add("Source structure could not be verified. Fix unbalanced groups/environments or review caption numbering manually.");
        }
        return new Plan(revision, hash, List.copyOf(proposals), warnings.stream().limit(32).toList());
    }

    private static Prefix prefix(Group caption, String kind, boolean identity) {
        String body = caption.text(); int start = skipSpace(body, 0);
        if (body.startsWith("\\DocCaptionNumber", start)) {
            if (!identity) return null;
            int index = start + "\\DocCaptionNumber".length();
            Group word = group(body, index, '{', '}'); if (word == null) return null;
            Group number = group(body, word.to(), '{', '}'); if (number == null) return null;
            Group punctuation = group(body, number.to(), '{', '}'); if (punctuation == null) return null;
            if (!matchesKind(word.text(), kind) || !number.text().matches("[0-9]+(?:\\.[0-9]+)*") || number.text().length() > 40 || !punctuation.text().matches("[.:)]{0,2}")) return null;
            return new Prefix(start, punctuation.to(), word.text(), number.text());
        }
        var match = PLAIN.matcher(body.substring(start));
        if (!match.find() || !matchesKind(match.group(1), kind) || match.group(2).length() > 40 || start + match.end() >= body.length()) return null;
        return new Prefix(start, start + match.end(), match.group(1), match.group(2));
    }

    private static boolean matchesKind(String word, String kind) {
        return kind.equals("figure") ? List.of("figure", "fig", "fig.").contains(word.toLowerCase(java.util.Locale.ROOT)) : word.equalsIgnoreCase("table");
    }

    static String apply(String source, int revision, String expectedHash, List<String> ids, List<String> warnings) {
        Plan plan = preview(source, revision, warnings);
        if (!plan.sourceHash().equals(expectedHash)) throw new ApiException(HttpStatus.CONFLICT, "The source changed. Preview caption repairs again.");
        if (ids == null || ids.isEmpty() || ids.size() > 100 || new java.util.HashSet<>(ids).size() != ids.size())
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose one or more current caption repairs.");
        var selected = plan.proposals().stream().filter(proposal -> ids.contains(proposal.id())).sorted(Comparator.comparingInt(Proposal::start).reversed()).toList();
        if (selected.size() != ids.size()) throw new ApiException(HttpStatus.CONFLICT, "A caption proposal changed. Preview repairs again.");
        StringBuilder result = new StringBuilder(source);
        for (var proposal : selected) result.replace(proposal.start(), proposal.end(), proposal.after());
        return result.toString();
    }

    private static int skipSpace(String source, int index) { while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++; return index; }
    private static int lineEnd(String source, int index) { int end = source.indexOf('\n', index); return end < 0 ? source.length() : end + 1; }
    private static int literalEnd(String source, String delimiter, int index) {
        while (index < source.length()) {
            if (source.charAt(index) == '\\') { index += 2; continue; }
            if (source.charAt(index) == '%') { index = lineEnd(source, index); continue; }
            if (source.startsWith(delimiter, index)) return index;
            index++;
        }
        return -1;
    }
    private static Group group(String source, int index, char open, char close) {
        if (index >= source.length() || source.charAt(index) != open) return null;
        int start = index + 1, depth = 1, braces = 0;
        for (int cursor = start; cursor < source.length(); cursor++) {
            char value = source.charAt(cursor);
            if (value == '\\') { cursor++; continue; }
            if (value == '%') { cursor = lineEnd(source, cursor) - 1; continue; }
            if (open == '[') {
                if (value == '{') braces++;
                else if (value == '}') { if (--braces < 0) return null; }
                if (braces > 128) return null;
                if (braces != 0) continue;
            }
            if (value == open && ++depth > 128) return null;
            if (value == close && --depth == 0) return new Group(start, cursor + 1, source.substring(start, cursor));
        }
        return null;
    }
}
