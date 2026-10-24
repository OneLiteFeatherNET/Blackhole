package net.onelitefeather.blackhole.backend.evidence.upload;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a file is plain text and nothing else: strictly valid UTF-8 (malformed input is refused), a leading byte order mark
 * tolerated, no NUL and no control character other than tab, line feed and carriage return, and not a document in disguise: text that
 * starts (after whitespace) with {@code <?xml}, {@code <svg}, {@code <html}, {@code <!doctype} or {@code <script} is refused, so a
 * browser that is ever pointed at the file has nothing active to run. Text is the fallback type, tried after every binary type failed
 * to match.
 */
public final class TextGuard {

    private static final List<String> DOCUMENT_STARTS = List.of("<?xml", "<svg", "<html", "<!doctype", "<script");

    /** @return {@code true} when the file is acceptable plain text */
    public static boolean isPlainText(Path file) throws IOException {
        return isPlainText(Files.readAllBytes(file));
    }

    public static boolean isPlainText(byte[] bytes) {
        int from = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, from, bytes.length - from))
                    .toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        String head = text.stripLeading();
        head = head.substring(0, Math.min(head.length(), 16)).toLowerCase(Locale.ROOT);
        for (String start : DOCUMENT_STARTS) {
            if (head.startsWith(start)) {
                return false;
            }
        }
        return true;
    }

    private TextGuard() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
