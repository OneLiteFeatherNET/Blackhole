package net.onelitefeather.blackhole.backend.live;

/**
 * Whether a player session is still alive. It is a read: it never slides the session's idle timer, so keep-alives cannot keep a
 * session open.
 */
public interface LiveSessions {

    /** @param sessionKey the session's token hash, never the token */
    boolean alive(String sessionKey);
}
