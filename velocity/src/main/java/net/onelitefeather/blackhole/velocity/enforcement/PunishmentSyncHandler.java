package net.onelitefeather.blackhole.velocity.enforcement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the punishment sync messages of the backend: keeps the mirror current and, for a newly
 * active BAN or SERVER punishment, removes the affected player if they are connected to this proxy -
 * regardless of which proxy the staff member used.
 */
public final class PunishmentSyncHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishmentSyncHandler.class);

    private final PunishmentMirror mirror;
    private final EnforcementActions actions;

    public PunishmentSyncHandler(PunishmentMirror mirror, EnforcementActions actions) {
        this.mirror = mirror;
        this.actions = actions;
    }

    public void accept(PunishmentSyncMessage message) {
        try {
            this.mirror.apply(message);
            if (message.isSet()) {
                this.actions.enforce(message);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Could not handle the punishment sync message for section {}: {}", message.section(), e.getMessage());
        }
    }
}
