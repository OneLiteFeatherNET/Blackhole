package net.onelitefeather.blackhole.backend.intel;

/**
 * A list refresh that must not be applied: a refused URL or address, a failed download, a malformed list. The message
 * is short and safe to show staff; it never carries the list body.
 */
public class ListRefreshException extends RuntimeException {

    public ListRefreshException(String message) {
        super(message);
    }

    public ListRefreshException(String message, Throwable cause) {
        super(message, cause);
    }
}
