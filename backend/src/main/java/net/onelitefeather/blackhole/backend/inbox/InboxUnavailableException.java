package net.onelitefeather.blackhole.backend.inbox;

/** The inbox could not be read from the database; {@link InboxService} turns it into the error variant. */
public class InboxUnavailableException extends RuntimeException {

    public InboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
