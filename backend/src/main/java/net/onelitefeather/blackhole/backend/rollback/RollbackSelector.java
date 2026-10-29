package net.onelitefeather.blackhole.backend.rollback;

import java.util.UUID;

/**
 * Which staff actor a rollback undoes. A linked staff member is selected by their {@code oid}; an
 * in-game account that is not linked to anybody can only be selected by its Minecraft UUID, because
 * its proxy writes carry the proxy's {@code oid} and name no person.
 */
public sealed interface RollbackSelector {

    /** A HUMAN staff actor, matched by {@code COALESCE(actor_oid, recorded_by_oid)}. */
    record Staff(UUID oid) implements RollbackSelector {
    }

    /** The Minecraft account of an unlinked in-game actor: rows with that {@code source} and no {@code actor_oid}. */
    record UnlinkedAccount(UUID minecraftUuid) implements RollbackSelector {
    }

    static RollbackSelector staffActor(UUID oid) {
        return new Staff(oid);
    }

    static RollbackSelector unlinkedInGame(UUID minecraftUuid) {
        return new UnlinkedAccount(minecraftUuid);
    }
}
