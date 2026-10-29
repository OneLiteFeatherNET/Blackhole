package net.onelitefeather.blackhole.backend.areaban;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An {@link AreaBanStore} held in memory, with the same one-unlifted-ban-per-area rule as the partial unique index. */
class InMemoryAreaBanStore implements AreaBanStore {

    private final List<AreaBan> bans = new ArrayList<>();

    @Override
    public synchronized boolean insert(AreaBan ban) {
        if (findUnlifted(ban.kind(), ban.value()).isPresent()) {
            return false;
        }
        this.bans.add(ban);
        return true;
    }

    @Override
    public synchronized Optional<AreaBan> findById(UUID id) {
        return this.bans.stream().filter(ban -> ban.id().equals(id)).findFirst();
    }

    @Override
    public synchronized Optional<AreaBan> findUnlifted(AreaBan.Kind kind, String value) {
        return this.bans.stream()
                .filter(ban -> ban.kind() == kind && ban.value().equals(value) && ban.liftedAt() == null)
                .findFirst();
    }

    @Override
    public synchronized boolean lift(AreaBan lifted) {
        for (int i = 0; i < this.bans.size(); i++) {
            if (this.bans.get(i).id().equals(lifted.id()) && this.bans.get(i).liftedAt() == null) {
                this.bans.set(i, lifted);
                return true;
            }
        }
        return false;
    }
}
