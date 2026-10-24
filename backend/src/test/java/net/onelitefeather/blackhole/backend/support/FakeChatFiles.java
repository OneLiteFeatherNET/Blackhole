package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.casetimeline.ChatFiles;
import net.onelitefeather.blackhole.backend.evidence.EvidenceProvenance;
import net.onelitefeather.blackhole.backend.security.Attribution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The evidence side of the chat for the conversation tests: files a test stages by hand, the rules {@link ChatFiles#resolve} states
 * (staged, of this scope, not used before) and a record of what the conversation asked for.
 */
public final class FakeChatFiles implements ChatFiles {

    private final Map<UUID, Staged> staged = new LinkedHashMap<>();
    private final List<UUID> used = new ArrayList<>();
    private final List<UUID> bound = new ArrayList<>();
    private final List<UUID> deleted = new ArrayList<>();
    private boolean playerFilesDeleted;
    private boolean failDeletes;

    private record Staged(String scope, File file) {
    }

    /** Stages a PNG for the scope and returns its id. */
    public UUID stage(String scope, String name, EvidenceProvenance provenance) {
        UUID id = UUID.randomUUID();
        this.staged.put(id, new Staged(scope, new File(id, name, "image/png", 1234, 32, 20, provenance)));
        return id;
    }

    /** The ids a message bound. */
    public List<UUID> bound() {
        return this.bound;
    }

    /** The ids that were deleted for a redaction. */
    public List<UUID> deleted() {
        return this.deleted;
    }

    public boolean playerFilesDeleted() {
        return this.playerFilesDeleted;
    }

    /** Makes the next deletions fail, as an unreachable blob store would. */
    public void failDeletes() {
        this.failDeletes = true;
    }

    /** Marks a file as sent already, as a message that claimed it would. */
    public void markUsed(UUID id) {
        this.used.add(id);
    }

    @Override
    public int maxPerMessage() {
        return 5;
    }

    @Override
    public Resolved resolve(String punishmentIdentifier, String scope, List<UUID> attachmentIds) {
        List<File> files = new ArrayList<>();
        for (UUID id : attachmentIds) {
            Staged found = this.staged.get(id);
            if (found == null || !found.scope().equals(scope) || this.used.contains(id)) {
                return new Resolved(List.of(), "an attachment is unknown, was already sent, or belongs to another conversation");
            }
            files.add(found.file());
        }
        return new Resolved(files, null);
    }

    @Override
    public void bound(String punishmentIdentifier, Collection<UUID> attachmentIds) {
        this.bound.addAll(attachmentIds);
        this.used.addAll(attachmentIds);
    }

    @Override
    public boolean deleteFiles(String punishmentIdentifier, Collection<UUID> attachmentIds, Attribution by) {
        if (this.failDeletes) {
            return false;
        }
        this.deleted.addAll(attachmentIds);
        return true;
    }

    @Override
    public boolean deletePlayerFiles(String punishmentIdentifier, Attribution by) {
        if (this.failDeletes) {
            return false;
        }
        this.playerFilesDeleted = true;
        return true;
    }
}
