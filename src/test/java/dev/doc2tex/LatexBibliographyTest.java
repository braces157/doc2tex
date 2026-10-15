package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LatexBibliographyTest {
    private BibliographyAssets assets() { return new BibliographyAssets("refs.bib", "@misc{known}".getBytes(StandardCharsets.UTF_8), new byte[]{1}, new byte[]{2}); }
    @Test void checksLiteralKeysAndShowsExactSetupWithoutEditingProseCommentsOrCode() {
        String source = "\\documentclass{article}\n\\begin{document}\nProse: keep cite{prose} unchanged.\n"
                + "% \\cite{comment}\n\\verb|\\cite{inline-code}|\n$\\cite{math}$\n"
                + "\\begin{verbatim}\n\\cite{code}\n\\end{verbatim}\n"
                + "\\cite[see][p. 2]{known,missing,% a comment\nsecond}\n\\textbf{\\cite{nested}}\n\\nocite{*}\n\\cite{\\dynamic}\n\\end{document}";
        var inspection = LatexBibliography.inspect(source, Set.of("known")); assertTrue(inspection.verified());
        assertEquals(java.util.List.of("missing", "second", "nested"), inspection.unknownKeys()); assertTrue(inspection.warnings().stream().anyMatch(warning -> warning.contains("Dynamic")));
        var plan = LatexBibliography.plan(source, 3, assets()); assertEquals(2, plan.changes().size());
        for (var change : plan.changes()) assertTrue(source.contains(change.before()));
        String repaired = LatexBibliography.apply(source, 3, plan.sourceHash(), assets());
        assertTrue(repaired.contains("Prose: keep cite{prose} unchanged.")); assertTrue(repaired.contains("\\cite{code}"));
        assertTrue(repaired.contains("\\usepackage{fontspec}\n\\begin{document}")); assertTrue(repaired.contains("\\bibliography{bibliography/references}"));
        assertTrue(LatexBibliography.plan(repaired, 4, assets()).changes().isEmpty());
        assertThrows(ApiException.class, () -> LatexBibliography.apply(source + "\n% newer", 3, plan.sourceHash(), assets()));
    }

    @Test void refusesCustomExistingAndUnbalancedSetupWhileNoAttachmentRemainsReadOnly() {
        String source = "\\documentclass{article}\n\\begin{document}\nBody\n\\end{document}";
        assertTrue(LatexBibliography.plan(source, 0, null).changes().isEmpty());
        for (String modified : java.util.List.of(source + "{", source.replace("\\begin{document}", "\\begin {document}"),
                "\\usepackage[backend=biber]{biblatex}\n" + source, source.replace("Body", "\\begin{thebibliography}{9}\\bibitem{x}Entry\\end{thebibliography}"),
                source.replace("Body", "\\bibliography{custom}"))) assertTrue(LatexBibliography.plan(modified, 0, assets()).changes().isEmpty(), modified);
    }
}
