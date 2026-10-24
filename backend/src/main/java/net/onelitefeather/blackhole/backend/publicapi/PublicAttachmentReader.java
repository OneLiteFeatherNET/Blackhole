package net.onelitefeather.blackhole.backend.publicapi;

import java.util.Optional;
import java.util.UUID;

/**
 * Which chat file a player may download. The rule is part of the query, never of the caller: a file is visible when it belongs to a
 * message of this punishment that the player can see (a {@code PLAYER} visible message, not deleted, not redacted), or when it is the
 * image of an active share of this punishment. Anything else, including another punishment's id, is absent.
 */
public interface PublicAttachmentReader {

    /**
     * The stored file behind an id.
     *
     * @param identifier  the evidence id
     * @param fileKey     the storage key, never shown to the player
     * @param name        the sanitised display name
     * @param contentType the type detected from the bytes
     * @param size        the stored size in bytes
     */
    record Visible(UUID identifier, String fileKey, String name, String contentType, long size) {
    }

    /**
     * @param id the attachment id of a message file, or the share id of a shared image
     * @return the file, or empty when this punishment may not download it under this id
     */
    Optional<Visible> find(String punishmentIdentifier, UUID id);
}
