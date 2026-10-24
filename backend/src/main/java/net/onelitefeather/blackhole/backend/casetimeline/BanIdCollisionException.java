package net.onelitefeather.blackhole.backend.casetimeline;

/** The new Ban ID hash already belongs to another punishment; the caller draws another candidate. Carries no Ban ID. */
public class BanIdCollisionException extends RuntimeException {

    public BanIdCollisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
