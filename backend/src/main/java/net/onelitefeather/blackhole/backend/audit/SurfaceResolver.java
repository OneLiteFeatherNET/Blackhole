package net.onelitefeather.blackhole.backend.audit;

import net.onelitefeather.blackhole.backend.security.Actor;

/**
 * Derives the surface of a staff action from the verified caller. The request body never names it.
 * The panel-versus-API split is a declaration (the panel sends its header), not a verification.
 */
public final class SurfaceResolver {

    private SurfaceResolver() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param actor        the verified caller
     * @param onBehalfOf   whether the request vouches for a staff member other than the caller
     * @return {@link Surface#IN_GAME} for a proxy vouching for staff, {@link Surface#PANEL} for a person sending the
     * panel header, {@link Surface#API} otherwise
     */
    public static Surface resolve(Actor actor, boolean onBehalfOf) {
        if (actor.proxy() && onBehalfOf) {
            return Surface.IN_GAME;
        }
        if (actor.panel()) {
            return Surface.PANEL;
        }
        return Surface.API;
    }
}
