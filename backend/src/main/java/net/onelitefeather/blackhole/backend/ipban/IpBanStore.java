package net.onelitefeather.blackhole.backend.ipban;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of IP bans. The service and the firewall work through this interface; tests use an in-memory fake. */
public interface IpBanStore {

    Optional<IpBanRecord> find(UUID id);

    /** Every row that is active at {@code now}. */
    List<IpBanRecord> active(long now);

    /** The newest rows, active or not, at most {@code limit}. */
    List<IpBanRecord> newest(int limit);

    /**
     * @return false when an active ban already holds the same canonical range (the partial unique index refused it)
     */
    boolean insert(IpBanRecord record);

    /** Lifts an active ban. @return false when there was no active ban with that id */
    boolean lift(UUID id, UUID liftedBy, String reason, long at);

    /** Marks the expired, not yet lifted rows of one range as lifted, so a new ban of that range can be stored. */
    int liftExpired(String range, long now);

    /** Deletes lifted rows whose lift is before {@code cutoff}, and expired rows that expired before it. */
    int deleteClosedBefore(long cutoff);
}
