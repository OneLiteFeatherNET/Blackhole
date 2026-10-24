package net.onelitefeather.blackhole.backend.evidence.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * A best-effort refusal of PDFs that can run something or cannot be scanned: the header {@code %PDF-} must appear in the first
 * kilobyte, and the file must not use the names {@code /JavaScript}, {@code /JS}, {@code /Launch}, {@code /OpenAction}, {@code /AA},
 * {@code /EmbeddedFile}, {@code /RichMedia}, {@code /XFA}, {@code /SubmitForm} or {@code /Encrypt} (an encrypted file cannot be scanned).
 *
 * <p>This is defence in depth, not a guarantee: names can be hidden in compressed object streams. The real controls are the virus
 * scan and the delivery rules (a PDF is only ever served as a download, sandboxed, with {@code nosniff}). Names are read the way a PDF
 * reader reads them, including {@code #xx} escapes, so {@code /#4AavaScript} is {@code /JavaScript}. PDF metadata (author, producer)
 * cannot be stripped and is retained.</p>
 */
public final class PdfGuard {

    /** What the check found. */
    public enum Verdict {
        OK,
        /** No {@code %PDF-} header in the first kilobyte. */
        NOT_A_PDF,
        /** The file names active content or encryption. */
        ACTIVE_CONTENT
    }

    private static final int HEADER_WINDOW = 1024;
    private static final int MAX_NAME = 64;
    private static final Set<String> FORBIDDEN = Set.of("JavaScript", "JS", "Launch", "OpenAction", "AA", "EmbeddedFile", "RichMedia",
            "XFA", "SubmitForm", "Encrypt");

    public static Verdict check(Path file) throws IOException {
        return check(Files.readAllBytes(file));
    }

    public static Verdict check(byte[] pdf) {
        if (!hasHeader(pdf)) {
            return Verdict.NOT_A_PDF;
        }
        int i = 0;
        while (i < pdf.length) {
            if (pdf[i] != '/') {
                i++;
                continue;
            }
            StringBuilder name = new StringBuilder();
            int j = i + 1;
            while (j < pdf.length && !isDelimiter(pdf[j]) && name.length() <= MAX_NAME) {
                if (pdf[j] == '#' && j + 2 < pdf.length && hex(pdf[j + 1]) >= 0 && hex(pdf[j + 2]) >= 0) {
                    name.append((char) (hex(pdf[j + 1]) << 4 | hex(pdf[j + 2])));
                    j += 3;
                } else {
                    name.append((char) (pdf[j] & 0xFF));
                    j++;
                }
            }
            if (FORBIDDEN.contains(name.toString())) {
                return Verdict.ACTIVE_CONTENT;
            }
            i = Math.max(j, i + 1);
        }
        return Verdict.OK;
    }

    private static boolean hasHeader(byte[] pdf) {
        int last = Math.min(pdf.length, HEADER_WINDOW) - 5;
        for (int i = 0; i <= last; i++) {
            if (pdf[i] == '%' && pdf[i + 1] == 'P' && pdf[i + 2] == 'D' && pdf[i + 3] == 'F' && pdf[i + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    private static boolean isDelimiter(byte b) {
        return switch (b) {
            case 0, 9, 10, 12, 13, 32, '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true;
            default -> false;
        };
    }

    private static int hex(byte b) {
        return Character.digit(b, 16);
    }

    private PdfGuard() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
