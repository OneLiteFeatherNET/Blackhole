package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

/**
 * The {@code evidence} section of {@code config.json}: what the proxy may attach to punishments.
 */
public final class EvidenceConfig {

    /** The optional, off-by-default chat buffer; see {@link ChatCaptureConfig}. */
    private ChatCaptureConfig chatCapture;

    public EvidenceConfig() {
        this.chatCapture = new ChatCaptureConfig();
    }

    /**
     * @return the chat capture settings; off if the section is missing
     */
    public @NotNull ChatCaptureConfig getChatCapture() {
        return chatCapture == null ? new ChatCaptureConfig() : chatCapture;
    }
}
