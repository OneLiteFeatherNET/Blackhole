package net.onelitefeather.blackhole.backend.publicbans;

/**
 * The route templates of the public ban list. A problem body names the template as its {@code instance}, never the
 * request path, because the path carries the submitted hash.
 */
public final class PublicBanRoutes {

    public static final String LIST = "/public/bans";
    public static final String SUMMARY = "/public/bans/summary";
    public static final String ENTRY = "/public/bans/{hash}";

    private PublicBanRoutes() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The template a request path belongs to; anything under the prefix that is not a fixed route is the entry route. */
    public static String template(String path) {
        return switch (label(path)) {
            case "list" -> LIST;
            case "summary" -> SUMMARY;
            default -> ENTRY;
        };
    }

    /** A bounded metric label of a request path: {@code list}, {@code summary} or {@code entry}. */
    public static String label(String path) {
        if (path.equals(LIST) || path.equals(LIST + "/")) {
            return "list";
        }
        return path.equals(SUMMARY) || path.equals(SUMMARY + "/") ? "summary" : "entry";
    }
}
