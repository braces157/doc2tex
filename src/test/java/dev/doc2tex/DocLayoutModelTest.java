package dev.doc2tex;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocLayoutModelTest {
    @TempDir Path directory;

    @Test void rejectsWrongModelBeforeOpeningNativeSession() throws Exception {
        Path model = directory.resolve("inference.onnx");
        Files.writeString(model, "untrusted or incomplete model");
        var error = assertThrows(IOException.class, () -> new DocLayoutModel(model));
        assertTrue(error.getMessage().contains("checksum"));
    }

    @Test void regionsRejectInvalidLabelsCoordinatesConfidenceAndOrder() {
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("unknown", .9f, 0, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", Float.NaN, 0, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", 1.1f, 0, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", .9f, -.1f, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", .9f, 0, 0, 1.1f, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", .9f, 0, Float.NaN, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", .9f, .5f, 0, .5f, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DocLayoutModel.Region("text", .9f, 0, 0, 1, 1, -1));
    }

    @Test void roundsClampsAndOrdersOriginalPixelBoxesWithoutReorderingSpatially() throws Exception {
        var regions = DocLayoutModel.regions(new float[][] {
                {22, .9f, 100.5f, 120, 200.5f, 140, 2},
                {6, .8f, -4, 170, 240, 220, 1},
                {17, .5f, 10, 10, 50, 30, 0},
                {-1, .9f, 10, 10, 50, 30, 3},
                {22, .9f, 50, 20, 40, 30, 4}
        }, 200, 200);
        assertEquals(List.of("doc_title", "text"), regions.stream().map(DocLayoutModel.Region::label).toList());
        assertEquals(1, regions.getFirst().order());
        assertEquals(0, regions.getFirst().left());
        assertEquals(1, regions.getFirst().right());
        assertEquals(1, regions.getFirst().bottom());
        assertEquals(.5f, regions.get(1).left());
        assertEquals(1, regions.get(1).right());
        assertTrue(regions.getFirst().top() > regions.get(1).top());
    }

    @Test void removesDuplicatesTinyBoxesReferencesAndInlineFormulasButKeepsVisualRegions() throws Exception {
        var regions = DocLayoutModel.regions(new float[][] {
                {22, .95f, 10, 10, 90, 40, 0},
                {22, .8f, 11, 11, 89, 39, 1},
                {15, .9f, 20, 15, 40, 30, 2},
                {18, .9f, 10, 50, 90, 80, 3},
                {17, .9f, 10, 90, 15, 100, 4},
                {14, .9f, 10, 10, 90, 40, 5},
                {17, .9f, 10, 120, 90, 140, 6}
        }, 200, 200);
        assertEquals(List.of("text", "image", "paragraph_title"), regions.stream().map(DocLayoutModel.Region::label).toList());
        assertEquals(List.of(0, 5, 6), regions.stream().map(DocLayoutModel.Region::order).toList());
    }

    @Test void dropsHugeLandscapeImageWithoutLosingRealTextOrSoleImage() throws Exception {
        float[] image = {14, .9f, 0, 0, 190, 95, 0};
        var regions = DocLayoutModel.regions(new float[][] {image, {22, .9f, 10, 10, 180, 30, 1}}, 200, 100);
        assertEquals(List.of("text"), regions.stream().map(DocLayoutModel.Region::label).toList());
        assertEquals("image", DocLayoutModel.regions(new float[][] {image}, 200, 100).getFirst().label());
    }

    @Test void malformedModelOutputFailsExplicitly() {
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{22, .9f}}, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{22, .9f, 0, 0, Float.NaN, 20, 0}}, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{25, .9f, 0, 0, 20, 20, 0}}, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{22, .9f, 0, 0, 20, 20, 1.5f}}, 200, 200));
    }

    @Test @org.junit.jupiter.api.Tag("ai") void actualCpuModelMatchesSourcePageProbeAndSurvivesInterruptedCall() throws Exception {
        Path model = Path.of(System.getProperty("doc2tex.test-ai-model", ".tools/layout-ai/inference.onnx"));
        Path page = Path.of(System.getProperty("doc2tex.test-ai-image", "src/test/resources/ai-layout/probe.png"));
        assertTrue(Files.isRegularFile(model), "Run the AI layout setup before enabling the native smoke test");
        assertTrue(Files.isRegularFile(page), "The synthetic model probe fixture must be present");
        var image = ImageIO.read(page.toFile());
        assertNotNull(image);
        try (var detector = new DocLayoutModel(model)) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedException.class, () -> detector.detect(image, 30)); }
            finally { Thread.interrupted(); }
            var regions = detector.detect(image, 30);
            var text = regions.stream().filter(region -> region.label().equals("text")).findFirst().orElseThrow();
            assertEquals(.110f, text.left(), .025f);
            assertEquals(.046f / .773f, text.top(), .025f);
            assertEquals(.466f, text.right(), .025f);
            assertEquals(.062f / .773f, text.bottom(), .025f);
            assertTrue(text.confidence() > .5f);
            assertTrue(regions.stream().anyMatch(region -> region.label().equals("number") && region.top() > .8f));
            assertEquals(regions.stream().map(DocLayoutModel.Region::order).sorted().toList(), regions.stream().map(DocLayoutModel.Region::order).toList());
        }
    }
}
