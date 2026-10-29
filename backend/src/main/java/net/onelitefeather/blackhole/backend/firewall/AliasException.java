package net.onelitefeather.blackhole.backend.firewall;

/**
 * A failed firewall operation. {@link #operation()} is one of {@code list}, {@code add}, {@code delete},
 * {@code reconfigure} or {@code read_back}, the tag of the error counter. The message never contains an address or a
 * credential.
 */
public class AliasException extends RuntimeException {

    private final String operation;

    public AliasException(String operation, String message) {
        super(message);
        this.operation = operation;
    }

    public String operation() {
        return this.operation;
    }
}
