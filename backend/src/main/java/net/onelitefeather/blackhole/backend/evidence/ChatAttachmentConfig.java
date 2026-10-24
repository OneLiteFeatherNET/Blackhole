package net.onelitefeather.blackhole.backend.evidence;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;

import java.util.List;

/**
 * The {@code blackhole.chat.attachments.*} tunables: what the appeal chat accepts as a file. The intent of every property is
 * documented next to its default in {@code application.yml}. The limits bound stored state (size, count, quota); none of them is a
 * rate limit, the edge owns rates. Created eagerly so a bad value fails startup.
 */
@Context
@ConfigurationProperties("blackhole.chat.attachments")
public class ChatAttachmentConfig {

    private long maxBytes = 10L * 1024 * 1024;
    private int maxPerMessage = 5;
    private List<String> allowedTypes = List.of("image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain");
    private int maxFilesPerAppeal = 50;
    private int maxStaged = 10;

    @PostConstruct
    void validate() {
        require(this.maxBytes > 0, "blackhole.chat.attachments.max-bytes must be positive");
        require(this.maxPerMessage > 0, "blackhole.chat.attachments.max-per-message must be positive");
        require(this.maxFilesPerAppeal > 0, "blackhole.chat.attachments.max-files-per-appeal must be positive");
        require(this.maxStaged > 0, "blackhole.chat.attachments.max-staged must be positive");
        require(this.allowedTypes != null && !this.allowedTypes.isEmpty(), "blackhole.chat.attachments.allowed-types must not be empty");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** The most bytes one chat file may have, whatever its type. */
    public long getMaxBytes() {
        return this.maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** The most files one message may name. */
    public int getMaxPerMessage() {
        return this.maxPerMessage;
    }

    public void setMaxPerMessage(int maxPerMessage) {
        this.maxPerMessage = maxPerMessage;
    }

    /** The detected content types a chat accepts; the client's type and the file name play no part. */
    public List<String> getAllowedTypes() {
        return this.allowedTypes;
    }

    public void setAllowedTypes(List<String> allowedTypes) {
        this.allowedTypes = allowedTypes;
    }

    /** The most chat files one appeal (or, for staff files, one case) may hold, staged and sent together. */
    public int getMaxFilesPerAppeal() {
        return this.maxFilesPerAppeal;
    }

    public void setMaxFilesPerAppeal(int maxFilesPerAppeal) {
        this.maxFilesPerAppeal = maxFilesPerAppeal;
    }

    /** The most files that may wait for a message at once in one scope. */
    public int getMaxStaged() {
        return this.maxStaged;
    }

    public void setMaxStaged(int maxStaged) {
        this.maxStaged = maxStaged;
    }
}
