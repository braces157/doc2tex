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

    @Test void roundsClampsAndOrdersOriginalPixelBoxesSpatially() throws Exception {
        var regions = DocLayoutModel.regions(new float[][] {
                {2, .9f, 100.5f, 120, 200.5f, 140},
                {11, .8f, -4, 170, 240, 220},
                {0, .5f, 10, 10, 50, 30},
                {-1, .9f, 10, 10, 50, 30},
                {2, .9f, 50, 20, 40, 30}
        }, 200, 200);
        assertEquals(List.of("text", "doc_title"), regions.stream().map(DocLayoutModel.Region::label).toList());
        assertEquals(List.of(0, 1), regions.stream().map(DocLayoutModel.Region::order).toList());
        assertEquals(.5f, regions.getFirst().left());
        assertEquals(1, regions.getFirst().right());
        assertEquals(0, regions.get(1).left());
        assertEquals(1, regions.get(1).right());
        assertEquals(1, regions.get(1).bottom());
        assertTrue(regions.getFirst().top() < regions.get(1).top());
    }

    @Test void removesDuplicatesTinyBoxesAndReferencesButKeepsVisualRegions() throws Exception {
        var regions = DocLayoutModel.regions(new float[][] {
                {2, .95f, 10, 10, 90, 40},
                {2, .8f, 11, 11, 89, 39},
                {10, .9f, 10, 50, 90, 80},
                {0, .9f, 10, 90, 15, 100},
                {1, .9f, 10, 10, 90, 40},
                {0, .9f, 10, 120, 90, 140}
        }, 200, 200);
        assertEquals(List.of("text", "image", "paragraph_title"), regions.stream().map(DocLayoutModel.Region::label).toList());
        assertEquals(List.of(0, 1, 2), regions.stream().map(DocLayoutModel.Region::order).toList());
    }

    @Test void keepsAParagraphTitleInsideALargerTextBlock() throws Exception {
        var regions = DocLayoutModel.regions(new float[][] {
                {2, .9f, 10, 10, 180, 90},
                {0, .9f, 20, 14, 170, 30}
        }, 200, 100);
        assertEquals(List.of("text", "paragraph_title"), regions.stream().map(DocLayoutModel.Region::label).toList());
    }

    @Test void dropsHugeLandscapeImageWithoutLosingRealTextOrSoleImage() throws Exception {
        float[] image = {1, .9f, 0, 0, 190, 95};
        var regions = DocLayoutModel.regions(new float[][] {image, {2, .9f, 10, 10, 180, 30}}, 200, 100);
        assertEquals(List.of("text"), regions.stream().map(DocLayoutModel.Region::label).toList());
        assertEquals("image", DocLayoutModel.regions(new float[][] {image}, 200, 100).getFirst().label());
    }

    @Test void respectsDetectionCountAndRejectsMalformedModelOutput() throws Exception {
        var trimmed = DocLayoutModel.regions(new float[][] {{2, .9f, 10, 10, 90, 40}, {99, 99, 0, 0, 0, 0}}, 1, 200, 200);
        assertEquals(List.of("text"), trimmed.stream().map(DocLayoutModel.Region::label).toList());
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{2, .9f}}, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{2, .9f, 0, 0, Float.NaN, 20}}, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{23, .9f, 0, 0, 20, 20}}, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{2, .9f, 0, 0, 20, 20}}, 2, 200, 200));
        assertThrows(IOException.class, () -> DocLayoutModel.regions(new float[][] {{2, .9f, 0, 0, 20, 20}}, -1, 200, 200));
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
            var topLine = regions.stream().filter(region -> region.top() < .15f).findFirst().orElseThrow();
            assertEquals(.110f, topLine.left(), .025f);
            assertEquals(.046f / .773f, topLine.top(), .025f);
            assertEquals(.466f, topLine.right(), .025f);
            assertEquals(.062f / .773f, topLine.bottom(), .025f);
            assertTrue(topLine.confidence() > .5f);
            assertEquals(regions.stream().map(DocLayoutModel.Region::order).sorted().toList(), regions.stream().map(DocLayoutModel.Region::order).toList());
        }
    }
}
