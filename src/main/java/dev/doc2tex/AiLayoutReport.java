package dev.doc2tex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

record AiLayoutReport(Status status, String model, int totalPages, List<Page> pages, List<Heading> headings,
                      List<String> appliedHeadings, boolean twoColumns, int appliedColumnBreaks, List<String> warnings) {
    enum Status { ANALYZED, UNAVAILABLE, FAILED }
    record Page(int number, List<DocLayoutModel.Region> regions) {
        Page {
            regions = List.copyOf(regions);
            if (number < 1 || regions.size() > 300) throw new IllegalArgumentException("Invalid AI page report.");
        }
    }
    record Heading(int sourceParagraph, int page, String text, float confidence) {
        Heading {
            if (sourceParagraph < 1 || page < 1 || text == null || text.isBlank() || text.length() > 200
                    || !Float.isFinite(confidence) || confidence < .8f || confidence > 1)
                throw new IllegalArgumentException("Invalid visual heading match.");
        }
    }
    private static final JsonMapper JSON = JsonMapper.builder().build();

    AiLayoutReport {
        pages = List.copyOf(pages); headings = List.copyOf(headings);
        appliedHeadings = List.copyOf(appliedHeadings); warnings = List.copyOf(warnings);
        if (status == null || !"PP-DocLayoutV3".equals(model) || totalPages < 0 || pages.size() > 12
                || pages.size() > totalPages || headings.size() > 100 || appliedHeadings.size() > headings.size() || warnings.size() > 20
                || appliedColumnBreaks < 0 || appliedColumnBreaks > 100 || (!twoColumns && appliedColumnBreaks != 0)
                || (status != Status.ANALYZED && (!pages.isEmpty() || !headings.isEmpty() || !appliedHeadings.isEmpty() || twoColumns))
                || (status == Status.ANALYZED && pages.isEmpty())) throw new IllegalArgumentException("Invalid AI layout report.");
        for (String text : appliedHeadings) if (headings.stream().noneMatch(heading -> heading.text().equals(text)))
            throw new IllegalArgumentException("Applied headings must match source evidence.");
    }

    static AiLayoutReport unavailable(String reason) {
        return new AiLayoutReport(Status.UNAVAILABLE, "PP-DocLayoutV3", 0, List.of(), List.of(), List.of(), false, 0, List.of(reason));
    }
    static AiLayoutReport failed() {
        return new AiLayoutReport(Status.FAILED, "PP-DocLayoutV3", 0, List.of(), List.of(), List.of(), false, 0,
                List.of("AI layout analysis could not finish. Reinstall the local AI layout files with the setup script, then retry. Conversion used the original Word structure."));
    }

    AiLayoutReport applied(List<String> applied, int columnBreaks, boolean missingBreaks) {
        var notes = new ArrayList<>(warnings);
        if (applied.size() < headings.size()) notes.add("Some visual headings could not be matched safely in the converted document and were left unchanged.");
        if (missingBreaks) notes.add("Some explicit Word column breaks could not be mapped safely. Review where columns start.");
        return new AiLayoutReport(status, model, totalPages, pages, headings, applied, twoColumns, columnBreaks, notes);
    }

    AiLayoutReport unverified() {
        var notes = new ArrayList<>(warnings);
        notes.add("The applied AI layout decisions could not be verified. Compare the converted headings and columns with the original.");
        return new AiLayoutReport(status, model, totalPages, pages, headings, List.of(), twoColumns, 0, notes);
    }

    void save(Path output) throws IOException { Files.write(output.resolve("ai-layout.json"), JSON.writeValueAsBytes(this)); }

    static AiLayoutReport read(Path output) throws IOException {
        Path file = output.resolve("ai-layout.json"); JobStore.checkUnlinked(file);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("The AI layout report exceeds its limit.");
            return JSON.readValue(bytes, AiLayoutReport.class);
        }
    }

    List<DocumentInspector.Finding> findings() {
        var findings = new ArrayList<DocumentInspector.Finding>();
        if (status == Status.ANALYZED) findings.add(new DocumentInspector.Finding("ai-layout", "info", "AI layout assistance completed",
                model + " analyzed " + pages.size() + " of " + totalPages + " source pages. Restored " + appliedHeadings.size()
                        + " heading styles" + (twoColumns ? " and the source's two-column layout" : "")
                        + (appliedColumnBreaks > 0 ? ", including " + appliedColumnBreaks + " explicit column breaks" : "")
                        + ". Original text and equations stay editable. These are initial conversion decisions; later source edits take precedence."));
        else if (status == Status.UNAVAILABLE) findings.add(new DocumentInspector.Finding("ai-layout-unavailable", "info", "AI layout assistance unavailable", String.join(" ", warnings)));
        if (status != Status.UNAVAILABLE) for (int i = 0; i < warnings.size(); i++)
            findings.add(new DocumentInspector.Finding("ai-layout-review-" + i, "warning", "AI layout needs review", warnings.get(i)));
        return findings;
    }
}
