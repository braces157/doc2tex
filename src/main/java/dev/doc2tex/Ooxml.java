package dev.doc2tex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/** Shared OOXML Transitional and Strict namespace and measurement helpers. */
final class Ooxml {
    static final String W_TRANSITIONAL = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String W_STRICT = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    static final String M_TRANSITIONAL = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    static final String M_STRICT = "http://purl.oclc.org/ooxml/officeDocument/math";
    private static final Pattern MEASURE = Pattern.compile("^(\\d+(?:\\.\\d+)?)(mm|cm|in|pt|pc|pi)?$");
    private static final BigDecimal MM_PER_INCH = new BigDecimal("25.4");

    private Ooxml() {}

    static boolean isWord(String namespace) {
        return W_TRANSITIONAL.equals(namespace) || W_STRICT.equals(namespace);
    }

    static boolean isMath(String namespace) {
        return M_TRANSITIONAL.equals(namespace) || M_STRICT.equals(namespace);
    }

    /** Returns the root's WordprocessingML namespace (Transitional or Strict), or null if unsupported. */
    static String wordNamespace(Element root) {
        if (root == null) return null;
        String namespace = root.getNamespaceURI();
        return isWord(namespace) ? namespace : null;
    }

    static String mathNamespace(String wordNamespace) {
        return W_STRICT.equals(wordNamespace) ? M_STRICT : M_TRANSITIONAL;
    }

    /** Parses OOXML twips (bare integer) or ISO 29500 ST_UniversalMeasure (mm, cm, in, pt, pc, pi) into mm. */
    static BigDecimal measureMm(String raw, BigDecimal maxMm) {
        if (raw == null || raw.isEmpty() || raw.length() > 32) throw new IllegalArgumentException("Unsupported page measurement.");
        var match = MEASURE.matcher(raw);
        if (!match.matches()) throw new IllegalArgumentException("Unsupported page measurement.");
        String unit = match.group(2);
        BigDecimal mm;
        if (unit == null) {
            if (match.group(1).contains(".")) throw new IllegalArgumentException("Unsupported page measurement.");
            int twips = Integer.parseInt(match.group(1));
            if (twips < 0 || twips > 34015) throw new IllegalArgumentException("Unsupported page measurement.");
            mm = BigDecimal.valueOf(twips).multiply(MM_PER_INCH).divide(BigDecimal.valueOf(1440), 3, RoundingMode.HALF_UP);
        } else {
            BigDecimal value = new BigDecimal(match.group(1));
            mm = switch (unit) {
                case "mm" -> value.setScale(3, RoundingMode.HALF_UP);
                case "cm" -> value.multiply(BigDecimal.TEN).setScale(3, RoundingMode.HALF_UP);
                case "in" -> value.multiply(MM_PER_INCH).setScale(3, RoundingMode.HALF_UP);
                case "pt" -> value.multiply(MM_PER_INCH).divide(BigDecimal.valueOf(72), 3, RoundingMode.HALF_UP);
                case "pc", "pi" -> value.multiply(MM_PER_INCH).divide(BigDecimal.valueOf(6), 3, RoundingMode.HALF_UP);
                default -> throw new IllegalArgumentException("Unsupported page measurement.");
            };
        }
        if (mm.signum() < 0 || mm.compareTo(maxMm) > 0) throw new IllegalArgumentException("Unsupported page measurement.");
        BigDecimal normalized = mm.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }
}
