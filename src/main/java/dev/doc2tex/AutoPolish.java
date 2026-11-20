package dev.doc2tex;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic, prose-safe source and media repairs applied without altering user meaning. */
final class AutoPolish {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FILE_NAME = "auto-polish.json";
    private static final int REPORT_LIMIT = 256 * 1024;
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 8192;
    private static final Set<String> LITERAL_ENVS = Set.of("verbatim", "verbatim*", "Verbatim", "lstlisting", "minted");
    private static final Pattern ENV_TOKEN = Pattern.compile("\\\\(begin|end)\\{([A-Za-z*]+)\\}");
    private static final Pattern INCLUDE_GRAPHICS = Pattern.compile("\\\\includegraphics(?:\\[[^\\]]*\\])?\\{([^}]+)\\}");

    record Fix(String code, String title, String detail, int count) {}
    record Report(int sourceRevision, int appliedRevision, String sourceHash, List<Fix> fixes, List<String> skipped) {}
    record Result(String source, boolean changed, Report report) {}
    private record Span(int start, int end, String replacement) {}

    private AutoPolish() {}

    static Result apply(String source, Path output, List<String> referenceWarnings) {
        String current = source;
        var fixes = new ArrayList<Fix>();
        var skipped = new LinkedHashSet<String>();

        CaptionRepairs.Plan captionPlan = CaptionRepairs.preview(current, 0, referenceWarnings);
        if (!captionPlan.proposals().isEmpty()) {
            List<String> ids = captionPlan.proposals().stream().map(CaptionRepairs.Proposal::id).toList();
            current = CaptionRepairs.apply(current, 0, captionPlan.sourceHash(), ids, referenceWarnings);
            int count = ids.size();
            fixes.add(new Fix(
                    "duplicate-captions",
                    "Fixed duplicate figure/table numbers",
                    "Removed repeated numbers from " + count + " caption" + (count == 1 ? "" : "s") + " so LaTeX numbers them once.",
                    count));
        }

        current = convertImages(current, output, fixes, skipped);
        boolean changed = !current.equals(source);
        var report = new Report(0, changed ? 1 : 0, ReviewEvidence.digest(current), List.copyOf(fixes), List.copyOf(skipped));
        return new Result(current, changed, report);
    }

    private static String convertImages(String source, Path output, List<Fix> fixes, Set<String> skipped) {
        if (output == null) return source;
        Path normalizedOutput = output.toAbsolutePath().normalize();
        Path mediaRoot = normalizedOutput.resolve("media").normalize();
        String masked = maskCommentsAndLiteral(source);
        Matcher matcher = INCLUDE_GRAPHICS.matcher(masked);

        var replacements = new ArrayList<Span>();
        Map<String, String> convertedTargets = new LinkedHashMap<>();
        Map<String, Path> convertedSourceFiles = new LinkedHashMap<>();

        while (matcher.find()) {
            String target = matcher.group(1).strip();
            if (!isSafeMediaTarget(target)) continue;
            Path sourceFile = normalizedOutput.resolve(target).normalize();
            if (!sourceFile.startsWith(mediaRoot) || sourceFile.equals(mediaRoot)) continue;

            String fileName = sourceFile.getFileName().toString();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".emf") || lower.endsWith(".wmf") || lower.endsWith(".svg")) {
                skipped.add("Vector image " + fileName + " cannot be converted automatically; save it as PNG in Word.");
                continue;
            }
            if (!(lower.endsWith(".bmp") || lower.endsWith(".gif") || lower.endsWith(".tif") || lower.endsWith(".tiff"))) {
                continue;
            }

            String existing = convertedTargets.get(target);
            if (existing != null) {
                replacements.add(new Span(matcher.start(1), matcher.end(1), existing));
                continue;
            }

            try {
                JobStore.checkUnlinked(mediaRoot);
                JobStore.checkUnlinked(sourceFile);
                if (!Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS) || Files.size(sourceFile) > MAX_IMAGE_BYTES) {
                    skipped.add("Image " + fileName + " could not be converted automatically; save it as PNG in Word.");
                    continue;
                }
                Path pngTarget = convertRasterToPng(sourceFile, mediaRoot);
                if (pngTarget == null) {
                    skipped.add("Image " + fileName + " could not be converted automatically; save it as PNG in Word.");
                    continue;
                }
                String relativePng = normalizedOutput.relativize(pngTarget).toString().replace('\\', '/');
                convertedTargets.put(target, relativePng);
                convertedSourceFiles.put(target, sourceFile);
                replacements.add(new Span(matcher.start(1), matcher.end(1), relativePng));
            } catch (IOException | RuntimeException exception) {
                skipped.add("Image " + fileName + " could not be converted automatically; save it as PNG in Word.");
            }
        }

        if (replacements.isEmpty()) return source;
        StringBuilder updated = new StringBuilder(source);
        replacements.stream()
                .sorted(Comparator.comparingInt(Span::start).reversed())
                .forEach(span -> updated.replace(span.start(), span.end(), span.replacement()));
        String result = updated.toString();

        for (var entry : convertedSourceFiles.entrySet()) {
            if (!result.contains(entry.getKey())) {
                try {
                    JobStore.checkUnlinked(entry.getValue());
                    Files.deleteIfExists(entry.getValue());
                } catch (IOException | RuntimeException ignored) {
                    // Keeping the original image file does not affect compilation once main.tex points to PNG.
                }
            }
        }
        int count = convertedTargets.size();
        fixes.add(new Fix(
                "image-conversion",
                "Converted images for PDF compatibility",
                "Converted " + count + " image" + (count == 1 ? "" : "s") + " to PNG so PDF creation succeeds.",
                count));
        return result;
    }

    private static boolean isSafeMediaTarget(String target) {
        return target.startsWith("media/")
                && !target.contains("..")
                && !target.contains("\\")
                && target.codePoints().noneMatch(Character::isISOControl);
    }

    private static Path convertRasterToPng(Path sourceFile, Path mediaRoot) throws IOException {
        BufferedImage image;
        try (ImageInputStream input = ImageIO.createImageInputStream(sourceFile.toFile())) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                    return null;
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) return null;

        Path target = choosePngSibling(sourceFile, mediaRoot);
        if (target == null) return null;
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        JobStore.checkUnlinked(temp);
        try {
            if (!writePng(image, temp)) return null;
            if (!Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS) || Files.size(temp) == 0) return null;
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean writePng(BufferedImage image, Path target) throws IOException {
        if (ImageIO.write(image, "png", target.toFile())) return true;
        BufferedImage normalized = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return ImageIO.write(normalized, "png", target.toFile());
    }

    private static Path choosePngSibling(Path sourceFile, Path mediaRoot) {
        String name = sourceFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path direct = sourceFile.resolveSibling(stem + ".png").normalize();
        if (isUnusedSibling(direct, mediaRoot)) return direct;
        Path firstConverted = sourceFile.resolveSibling(stem + "-converted.png").normalize();
        if (isUnusedSibling(firstConverted, mediaRoot)) return firstConverted;
        for (int index = 2; index <= 100; index++) {
            Path candidate = sourceFile.resolveSibling(stem + "-converted-" + index + ".png").normalize();
            if (isUnusedSibling(candidate, mediaRoot)) return candidate;
        }
        return null;
    }

    private static boolean isUnusedSibling(Path candidate, Path mediaRoot) {
        if (!candidate.startsWith(mediaRoot)) return false;
        JobStore.checkUnlinked(candidate);
        return !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
    }

    static String maskCommentsAndLiteral(String source) {
        char[] chars = source.toCharArray();
        boolean comment = false;
        int slashes = 0;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\n' || c == '\r') {
                comment = false;
                slashes = 0;
                continue;
            }
            if (!comment && c == '%' && slashes % 2 == 0) comment = true;
            if (comment) chars[i] = ' ';
            slashes = c == '\\' ? slashes + 1 : 0;
        }
        String withoutComments = new String(chars);
        Matcher env = ENV_TOKEN.matcher(withoutComments);
        String activeLiteral = null;
        int literalStart = -1;
        while (env.find()) {
            String kind = env.group(1);
            String name = env.group(2);
            if (activeLiteral == null) {
                if ("begin".equals(kind) && LITERAL_ENVS.contains(name)) {
                    activeLiteral = name;
                    literalStart = env.start();
                }
            } else if ("end".equals(kind) && activeLiteral.equals(name)) {
                for (int i = literalStart; i < env.end(); i++) {
                    if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
                }
                activeLiteral = null;
                literalStart = -1;
            }
        }
        if (activeLiteral != null) {
            for (int i = literalStart; i < chars.length; i++) {
                if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    static void save(Path output, Report report) throws IOException {
        Path destination = output.resolve(FILE_NAME);
        Path temporary = output.resolve(FILE_NAME + ".tmp");
        JobStore.checkUnlinked(destination);
        JobStore.checkUnlinked(temporary);
        byte[] bytes = JSON.writeValueAsBytes(report);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        SourceVersionStore.forceDirectory(output);
    }

    static Report read(Path output) throws IOException {
        Path file = output.resolve(FILE_NAME);
        JobStore.checkUnlinked(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(REPORT_LIMIT + 1);
            if (bytes.length > REPORT_LIMIT) throw new IOException("The auto-polish report exceeds its limit.");
            Report report = JSON.readValue(bytes, Report.class);
            if (report == null || report.fixes() == null || report.skipped() == null || report.sourceHash() == null) {
                throw new IOException("The auto-polish report metadata is incomplete.");
            }
            return report;
        } catch (RuntimeException exception) {
            throw new IOException("The auto-polish report could not be read.", exception);
        }
    }
}
