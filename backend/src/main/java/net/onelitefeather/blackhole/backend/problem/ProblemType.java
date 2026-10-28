package net.onelitefeather.blackhole.backend.problem;

import io.micronaut.http.HttpStatus;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.Locale;

/**
 * The problem type URIs (design D2). One scheme: {@code BASE_URI} followed by a slug. The slug is the
 * machine code in kebab case when the error has one, otherwise the kind of the error. The URIs are
 * identifiers only; they need not resolve. Clients branch on {@code code}, never on the type.
 */
public final class ProblemType {

    /** The base of every problem type URI. */
    public static final String BASE_URI = "https://blackhole.onelitefeather.net/problems/";

    private ProblemType() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The type of an error that carries a machine code, such as {@code NOT_ELIGIBLE}. */
    public static String forCode(String code) {
        return BASE_URI + slug(code);
    }

    /** The type of an error without a code, named after its kind, such as {@code not-found}. */
    public static String forKind(ErrorKind kind) {
        return BASE_URI + slug(kind.name());
    }

    /** The type of a failure that has neither a code nor a kind, named after its HTTP status. */
    public static String forStatus(HttpStatus status) {
        return BASE_URI + statusSlug(status);
    }

    /** A human title for a machine code: {@code NOT_ELIGIBLE} becomes {@code Not eligible}. */
    public static String titleOf(String machine) {
        String words = machine.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String slug(String machine) {
        if (machine == null || !machine.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("not a machine code: " + machine);
        }
        return machine.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String statusSlug(HttpStatus status) {
        return switch (status.getCode()) {
            case 400 -> "validation";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 409 -> "conflict";
            case 500 -> "internal-error";
            case 503 -> "unavailable";
            default -> "http-" + status.getCode();
        };
    }
}
