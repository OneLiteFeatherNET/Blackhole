package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.security.Attribution;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * What the conversation needs from the evidence side to carry files, kept narrow so the case package depends on a few methods and not
 * on the blob store and the upload pipeline. A chat file is an evidence item that is <i>staged</i> by an upload (in no listing, count or
 * conversation) and <i>bound</i> by the message that names it, in the same statement that records the message.
 */
public interface ChatFiles {

    /** The staged files of an appeal (a player's) carry this correlation prefix, followed by the appeal id. */
    String PREFIX = "chat:";

    /** The scope of the files a player uploads for an appeal. */
    static String appealScope(UUID appealId) {
        return PREFIX + appealId;
    }

    /** The scope of the files staff upload for a case: the punishment, because a staff message belongs to no appeal. */
    static String caseScope(String punishmentIdentifier) {
        return PREFIX + punishmentIdentifier;
    }

    /**
     * A staged file as it goes into the message payload.
     *
     * @param attachmentId the evidence item id
     */
    record File(UUID attachmentId, String name, String contentType, long size, @Nullable Integer width, @Nullable Integer height,
                EvidenceProvenance provenance) {

        /** The payload entry of this file: id, name, type, size, dimensions and provenance; never a key, bytes or a hash. */
        public MessageAttachment toAttachment() {
            return new MessageAttachment(MessageAttachment.FILE, null, this.name, this.contentType, this.size, this.width, this.height,
                    this.attachmentId.toString(), this.provenance.name());
        }
    }

    /**
     * The files an {@code attachmentIds} list names.
     *
     * @param files   the files in the order named, empty when there is a {@code problem}
     * @param problem why the list cannot be bound ({@code null} when it can)
     */
    record Resolved(List<File> files, @Nullable String problem) {

        public boolean ok() {
            return this.problem == null;
        }
    }

    /** The most files one message may name. */
    int maxPerMessage();

    /**
     * Checks that every id is a staged chat file of this scope and of this punishment's owner and has not been bound before. Nothing
     * is claimed here: the claim happens in the statement that records the message.
     */
    Resolved resolve(String punishmentIdentifier, String scope, List<UUID> attachmentIds);

    /** Records that the files became case evidence (the {@code evidence.attached} events); never fails the message. */
    void bound(String punishmentIdentifier, Collection<UUID> attachmentIds);

    /**
     * Deletes the named files of the case with their blobs (reason {@code ERASURE}), for the redaction of a message. A file that is gone
     * already is skipped.
     *
     * @return {@code false} when a blob could not be deleted; nothing is then reported as done and the caller must not go on
     */
    boolean deleteFiles(String punishmentIdentifier, Collection<UUID> attachmentIds, @Nullable Attribution by);

    /** Deletes every {@code PLAYER} provenance chat file of the case, for the player-side erase. Same contract as {@link #deleteFiles}. */
    boolean deletePlayerFiles(String punishmentIdentifier, @Nullable Attribution by);

    /** No files at all: a conversation that was built without the evidence side (a test of the text rules). */
    ChatFiles NONE = new ChatFiles() {
        @Override
        public int maxPerMessage() {
            return 5;
        }

        @Override
        public Resolved resolve(String punishmentIdentifier, String scope, List<UUID> attachmentIds) {
            return new Resolved(List.of(), attachmentIds.isEmpty() ? null : "files are not available");
        }

        @Override
        public void bound(String punishmentIdentifier, Collection<UUID> attachmentIds) {
        }

        @Override
        public boolean deleteFiles(String punishmentIdentifier, Collection<UUID> attachmentIds, @Nullable Attribution by) {
            return true;
        }

        @Override
        public boolean deletePlayerFiles(String punishmentIdentifier, @Nullable Attribution by) {
            return true;
        }
    };
}
