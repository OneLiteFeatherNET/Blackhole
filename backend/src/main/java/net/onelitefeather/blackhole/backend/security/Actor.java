package net.onelitefeather.blackhole.backend.security;

import io.micronaut.core.annotation.Nullable;

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
 */
public record Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin, boolean panel) {

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy) {
        this(oid, displayName, kind, proxy, false, false);
    }

    public Actor(UUID oid, @Nullable String displayName, ActorKind kind, boolean proxy, boolean admin) {
        this(oid, displayName, kind, proxy, admin, false);
    }

    /** This caller, with the declared surface of the request. */
    public Actor withPanel(boolean panel) {
        return new Actor(this.oid, this.displayName, this.kind, this.proxy, this.admin, panel);
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
