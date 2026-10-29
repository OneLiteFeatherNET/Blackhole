package net.onelitefeather.blackhole.backend.security;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.audit.AuditReceipt;

import java.util.Optional;
import java.util.UUID;

/**
 * The verified caller of a request, taken from the validated token by {@link ActorResolver}.
 *
 * @param oid         the Entra object id claim
 * @param displayName the {@code name} claim, if present
 * @param kind        {@link ActorKind#HUMAN} for a delegated token, {@link ActorKind#SERVICE} for an app-only one
 * @param proxy       whether the principal holds the {@code PROXY} role and may therefore vouch for {@code onBehalfOf}
 * @param admin       whether the principal holds the {@code ADMIN} role, for rules such as "the author or an admin"
 * @param panel       whether the request declares the team panel as its surface ({@code X-Blackhole-Surface: panel});
 *                    a declaration, not verified, read by {@link ActorArgumentBinder}
 * @param discord     whether the principal holds the {@code DISCORD} role (the bot), which may act for a linked staff member
 *                    through {@code onBehalfOf} on the routes that {@link StaffDelegation} covers
 * @param receipt     whether the staff action of this request was recorded in the audit log; written by the recorder
 */
public record Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin, boolean panel,
                    boolean discord, AuditReceipt receipt) {

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy) {
        this(oid, displayName, kind, proxy, false, false, false, new AuditReceipt());
    }

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin) {
        this(oid, displayName, kind, proxy, admin, false, false, new AuditReceipt());
    }

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin, boolean panel) {
        this(oid, displayName, kind, proxy, admin, panel, false, new AuditReceipt());
    }

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin, boolean panel, AuditReceipt receipt) {
        this(oid, displayName, kind, proxy, admin, panel, false, receipt);
    }

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin, boolean panel, boolean discord) {
        this(oid, displayName, kind, proxy, admin, panel, discord, new AuditReceipt());
    }

    /** This caller, with the declared surface of the request; the receipt of the request is kept. */
    public Actor withPanel(boolean panel) {
        return new Actor(this.oid, this.displayName, this.kind, this.proxy, this.admin, panel, this.discord, this.receipt);
    }

    /** The attribution of a write the caller makes in its own name. */
    public Attribution attribution() {
        return new Attribution(this.oid, this.oid);
    }

    /**
     * The attribution of a write that may carry {@code onBehalfOf}.
     *
     * @return the attribution, or empty when {@code onBehalfOf} is given by a caller that is not the proxy
     */
    public Optional<Attribution> attribution(@Nullable UUID onBehalfOf) {
        if (onBehalfOf == null) {
            return Optional.of(attribution());
        }
        if (!this.proxy) {
            return Optional.empty();
        }
        return Optional.of(new Attribution(onBehalfOf, this.oid));
    }
}
