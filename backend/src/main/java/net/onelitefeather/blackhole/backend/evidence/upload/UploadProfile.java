package net.onelitefeather.blackhole.backend.evidence.upload;

/**
 * Which rules an upload goes through. A profile never changes the other's allowed types; both scan first and fail closed.
 */
public enum UploadProfile {

    /** Staff evidence: PNG and JPEG (re-encoded) and MP4 and WebM (as uploaded), limits from {@code blackhole.evidence.files.*}. */
    EVIDENCE,

    /**
     * Chat files of the appeal conversation: PNG and JPEG (re-encoded), WebP (EXIF and XMP stripped, never animated), PDF (no active
     * content) and UTF-8 text, limits from {@code blackhole.chat.attachments.*}. No video.
     */
    CHAT
}
