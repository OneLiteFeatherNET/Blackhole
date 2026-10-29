package net.onelitefeather.blackhole.backend.intel.names;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.intel.names.dto.NameHistoryDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps the name each account logged in with, and answers the names of one account and the accounts behind an old
 * name. Fed by {@code SessionService} at each new login; erasure of an owner removes its names.
 */
@Singleton
public class NameHistoryService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;
    /** The most owners one old-name lookup answers. */
    static final int MAX_OWNERS = 20;
    /** The largest offset a page may start at, so that the SQL offset and limit stay inside an int. */
    private static final long MAX_OFFSET = Integer.MAX_VALUE - MAX_PAGE_SIZE - 1L;
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");

    private final NameHistoryStore store;
    private final boolean enabled;

    public NameHistoryService(NameHistoryStore store, @Value("${blackhole.intel.enabled:true}") boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    /** Records a name seen at a login. Called by the session service after the session itself is stored. */
    public void record(String owner, UUID playerUuid, String playerName, long seenAt) {
        this.store.upsert(owner, playerUuid, playerName, seenAt);
    }

    /** One page of the names of an owner, newest last seen first. An owner without names is an empty page. */
    public NameHistoryDTO namesOf(String owner, Integer page, Integer size) {
        if (!this.enabled) {
            return switchedOff();
        }
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return error("page must be 0 or more and size 1 to 100", ErrorKind.VALIDATION);
        }
        if ((long) pageNumber * pageSize > MAX_OFFSET) {
            return error("page is past the last readable page", ErrorKind.VALIDATION);
        }
        List<NameRecord> rows = this.store.findByOwner(owner, pageSize + 1, pageNumber * pageSize);
        boolean hasMore = rows.size() > pageSize;
        List<NameHistoryDTO.NameEntry> names = new ArrayList<>();
        rows.stream().limit(pageSize)
                .forEach(r -> names.add(new NameHistoryDTO.NameEntry(r.name(), r.firstSeen(), r.lastSeen())));
        String playerUuid = rows.isEmpty() ? null : rows.getFirst().playerUuid().toString();
        return new NameHistoryDTO.Response(owner, playerUuid, names, pageNumber, pageSize, hasMore);
    }

    /** The accounts that used this name, matched without case and exactly, at most {@link #MAX_OWNERS}. */
    public NameHistoryDTO ownersOf(String name) {
        if (!this.enabled) {
            return switchedOff();
        }
        if (name == null || !PLAYER_NAME.matcher(name).matches()) {
            return error("name must be 1 to 16 letters, digits, underscores or dots", ErrorKind.VALIDATION);
        }
        List<NameHistoryDTO.OwnerEntry> owners = this.store.findOwnersOfName(name, MAX_OWNERS).stream()
                .map(o -> new NameHistoryDTO.OwnerEntry(o.ownerHash(), o.playerUuid().toString(), o.currentName(),
                        o.firstSeen(), o.lastSeen()))
                .toList();
        return new NameHistoryDTO.OwnerResponse(owners);
    }

    /** Erasure of an owner's names. Runs whether or not the feature is switched on. @return how many names were removed */
    public int eraseOwner(String owner) {
        return this.store.deleteByOwner(owner);
    }

    private static NameHistoryDTO.Error error(String message, ErrorKind kind) {
        return new NameHistoryDTO.Error(message, kind);
    }

    /** With blackhole.intel.enabled off the name routes answer 404 and nothing is read. */
    private static NameHistoryDTO.Error switchedOff() {
        return error("player sessions are switched off", ErrorKind.NOT_FOUND);
    }
}
