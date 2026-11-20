package dev.doc2tex;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** CPU adapter for the pinned PP-DocLayout-S (PicoDet-S) ONNX export. */
final class DocLayoutModel implements AutoCloseable {
    static final String MODEL_NAME = "PP-DocLayout-S";
    static final String SHA256 = "33688dbee1c23e34b81777e97cb428eb40f24b242c02b5f623484959e830aec8";
    private static final int SIZE = 480;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final List<String> LABELS = List.of("paragraph_title", "image", "text", "number", "abstract",
            "content", "figure_title", "formula", "table", "table_title", "reference", "doc_title", "footnote",
            "header", "algorithm", "footer", "seal", "chart_title", "chart", "formula_number", "header_image",
            "footer_image", "aside_text");
    private static final Set<String> VALID_REGION_LABELS = Set.of("paragraph_title", "image", "text", "number", "abstract",
            "content", "figure_title", "formula", "table", "table_title", "reference", "doc_title", "footnote",
            "header", "algorithm", "footer", "seal", "chart_title", "chart", "formula_number", "header_image",
            "footer_image", "aside_text", "display_formula", "inline_formula", "reference_content", "vertical_text",
            "vision_footnote");
    private static final Set<String> VISUAL_LABELS = Set.of("image", "table", "seal", "chart");
    private final OrtEnvironment environment;
    private final OrtSession session;

    record Region(String label, float confidence, float left, float top, float right, float bottom, int order) {
        Region {
            if (label == null || !VALID_REGION_LABELS.contains(label) || !Float.isFinite(confidence) || confidence < 0 || confidence > 1
                    || !Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom)
                    || left < 0 || top < 0 || right > 1 || bottom > 1 || left >= right || top >= bottom || order < 0) {
                throw new IllegalArgumentException("Invalid document layout region");
            }
        }
    }

    DocLayoutModel(Path model) throws IOException, OrtException {
        verifyModel(model);
        environment = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            options.setIntraOpNumThreads(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.toAbsolutePath().toString(), options);
        }
    }

    private static void verifyModel(Path model) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(model)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!SHA256.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("The AI layout model checksum does not match the supported " + MODEL_NAME + " export. Run the AI layout setup again.");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    synchronized List<Region> detect(BufferedImage image, int timeoutSeconds) throws OrtException, IOException, InterruptedException {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("AI layout timeout must be positive");
        if (Thread.interrupted()) throw new InterruptedException("AI layout detection interrupted");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        float[] pixels = pixels(image);
        var caller = Thread.currentThread();
        var active = new AtomicBoolean(true);
        var timedOut = new AtomicBoolean(false);
        var interrupted = new AtomicBoolean(false);
        var timer = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "doc2tex-layout-deadline");
            thread.setDaemon(true);
            return thread;
        });
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), new long[] {1, 3, SIZE, SIZE});
                var scale = OnnxTensor.createTensor(environment, new float[][] {{(float) SIZE / image.getHeight(), (float) SIZE / image.getWidth()}});
                var options = new OrtSession.RunOptions()) {
            var watchdog = timer.scheduleAtFixedRate(() -> {
                synchronized (options) {
                    if (!active.get()) return;
                    if (caller.isInterrupted()) interrupted.set(true);
                    if (System.nanoTime() >= deadline) timedOut.set(true);
                    if (interrupted.get() || timedOut.get()) {
                        try { options.setTerminate(true); }
                        catch (OrtException exception) { /* The completed run still checks the deadline below. */ }
                    }
                }
            }, 0, 25, TimeUnit.MILLISECONDS);
            try {
                try (var output = session.run(Map.of("image", input, "scale_factor", scale), Set.of("fetch_name_0", "fetch_name_1"), options)) {
                    checkDeadline(deadline, timedOut.get(), interrupted.get());
                    var boxesValue = output.get("fetch_name_0").orElseThrow(() -> new IOException("Missing AI layout box tensor")).getValue();
                    var countValue = output.get("fetch_name_1").orElseThrow(() -> new IOException("Missing AI layout count tensor")).getValue();
                    if (!(boxesValue instanceof float[][] rows)) throw new IOException("The AI layout model returned an unexpected box tensor");
                    int count;
                    if (countValue instanceof int[] ints && ints.length == 1) count = ints[0];
                    else if (countValue instanceof long[] longs && longs.length == 1) count = Math.toIntExact(longs[0]);
                    else throw new IOException("The AI layout model returned an unexpected count tensor");
                    return regions(rows, count, image.getWidth(), image.getHeight());
                }
            } catch (OrtException exception) {
                checkDeadline(deadline, timedOut.get(), interrupted.get());
                throw exception;
            } finally {
                synchronized (options) {
                    active.set(false);
                    watchdog.cancel(false);
                }
            }
        } finally {
            timer.shutdownNow();
        }
    }

    private static void checkDeadline(long deadline, boolean timedOut, boolean interrupted) throws IOException, InterruptedException {
        if (Thread.interrupted() || interrupted) throw new InterruptedException("AI layout detection interrupted");
        if (timedOut || System.nanoTime() >= deadline) throw new IOException("AI layout inference exceeded its time limit");
    }

    private static float[] pixels(BufferedImage image) {
        var resized = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        var graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, SIZE, SIZE, null);
        } finally { graphics.dispose(); }
        int plane = SIZE * SIZE;
        float[] data = new float[3 * plane];
        float factor = 1f / 255f;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int rgb = resized.getRGB(x, y), index = y * SIZE + x;
            data[index] = (((rgb >>> 16) & 255) * factor - MEAN[0]) / STD[0];
            data[plane + index] = (((rgb >>> 8) & 255) * factor - MEAN[1]) / STD[1];
            data[2 * plane + index] = ((rgb & 255) * factor - MEAN[2]) / STD[2];
        }
        return data;
    }

    static List<Region> regions(float[][] rows, int width, int height) throws IOException {
        return regions(rows, rows == null ? 0 : rows.length, width, height);
    }

    /** PaddleX PicoDet rectangular postprocessing in spatial reading order. */
    static List<Region> regions(float[][] rows, int count, int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Page dimensions must be positive");
        if (rows == null || count < 0 || count > rows.length) throw new IOException("The AI layout model returned an invalid detection count");
        var boxes = new ArrayList<Region>();
        for (int idx = 0; idx < count; idx++) {
            var row = rows[idx];
            if (row == null || row.length != 6) throw new IOException("The AI layout model returned an invalid box row");
            for (float value : row) if (!Float.isFinite(value)) throw new IOException("The AI layout model returned a non-finite box");
            if (row[0] < 0 || row[1] <= .5f) continue;
            int label = (int) row[0];
            if (label != row[0] || label >= LABELS.size() || row[1] > 1) {
                throw new IOException("The AI layout model returned an invalid label or confidence");
            }
            float left = (float) Math.max(0, Math.rint(row[2])), top = (float) Math.max(0, Math.rint(row[3]));
            float right = (float) Math.min(width, Math.rint(row[4])), bottom = (float) Math.min(height, Math.rint(row[5]));
            if (right <= left || bottom <= top) continue;
            boxes.add(new Region(LABELS.get(label), row[1], left / width, top / height, right / width, bottom / height, idx));
        }
        if (boxes.size() > 1) {
            double limit = width > height ? .82 : .93;
            var filtered = boxes.stream().filter(box -> !box.label().equals("image") || area(box) <= limit).toList();
            if (!filtered.isEmpty()) boxes = new ArrayList<>(filtered);
        }
        boxes.sort(Comparator.comparingDouble(Region::top).thenComparingDouble(Region::left));
        boxes.removeIf(box -> box.label().equals("reference"));
        boolean[] dropped = new boolean[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            var a = boxes.get(i);
            if (Math.round((a.right() - a.left()) * width) < 6 || Math.round((a.bottom() - a.top()) * height) < 6) dropped[i] = true;
            for (int j = i + 1; j < boxes.size(); j++) {
                if (dropped[i] || dropped[j]) continue;
                var b = boxes.get(j);
                float overlap = overlap(a, b);
                if (overlap > .7f) {
                    boolean different = !a.label().equals(b.label());
                    if (different && (VISUAL_LABELS.contains(a.label()) || VISUAL_LABELS.contains(b.label())
                            || a.label().equals("paragraph_title") || b.label().equals("paragraph_title"))
                            && ((!a.label().equals("table") && !b.label().equals("table"))
                            || (VISUAL_LABELS.contains(a.label()) && VISUAL_LABELS.contains(b.label())))) continue;
                    dropped[area(a) >= area(b) ? j : i] = true;
                }
            }
        }
        var result = new ArrayList<Region>();
        for (int i = 0; i < boxes.size(); i++) {
            if (!dropped[i]) {
                var box = boxes.get(i);
                result.add(new Region(box.label(), box.confidence(), box.left(), box.top(), box.right(), box.bottom(), result.size()));
            }
        }
        return List.copyOf(result);
    }

    private static float area(Region box) { return (box.right() - box.left()) * (box.bottom() - box.top()); }
    private static float overlap(Region a, Region b) {
        float intersection = Math.max(0, Math.min(a.right(), b.right()) - Math.max(a.left(), b.left()))
                * Math.max(0, Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top()));
        return intersection / Math.min(area(a), area(b));
    }

    @Override public synchronized void close() throws OrtException { session.close(); }
}
