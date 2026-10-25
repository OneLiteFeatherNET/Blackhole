package net.onelitefeather.blackhole.backend.kick;

import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.kick.dto.KickDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules of a kick request that need no database. Pure: the same request always gets the same
 * answer. Answers never repeat the rejected text, since it may be exactly the personal data the rule
 * keeps out.
 */
public final class KickValidator {

    /** Longest reason a kick may carry. */
    public static final int MAX_REASON_LENGTH = 200;

    /** The {@code Idempotency-Key} alphabet and length. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,80}");

    /** A backend server name: letters, digits and {@code _ . -}. */
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** A section key. */
    private static final Pattern SECTION_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private KickValidator() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** The scope the request names, {@code NETWORK} when it names none. */
    public static KickScope scopeOf(KickDTO.CreateRequest request) {
        return request.scope() == null ? KickScope.NETWORK : request.scope();
    }

    /** Checks the {@code Idempotency-Key} header. */
    public static Optional<KickDTO.Error> validateKey(String key) {
        if (key == null || key.isEmpty()) {
            return error(ErrorKind.VALIDATION, "the Idempotency-Key header is required");
        }
        if (!KEY.matcher(key).matches()) {
            return error(ErrorKind.VALIDATION, "the Idempotency-Key must be 1 to 80 characters of letters, digits, '.', '_', ':' and '-'");
        }
        return Optional.empty();
    }

    /** Checks a kick of one player. */
    public static Optional<KickDTO.Error> validate(KickDTO.CreateRequest request) {
        if (!OwnerHash.isValid(request.owner())) {
            return error(ErrorKind.VALIDATION, "owner must be a sha-512 hash");
        }
        Optional<KickDTO.Error> reason = validateReason(request.reason());
        if (reason.isPresent()) {
            return reason;
        }
        return validateTarget(scopeOf(request), request.target());
    }

    /** Checks a mass kick. */
    public static Optional<KickDTO.Error> validate(KickDTO.MassCreateRequest request) {
        if (request.scope() == null || request.scope() == KickScope.NETWORK) {
            return error(ErrorKind.KICK_TARGET_INVALID, "a mass kick needs the scope SERVER or SECTION");
        }
        Optional<KickDTO.Error> reason = validateReason(request.reason());
        if (reason.isPresent()) {
            return reason;
        }
        return validateTarget(request.scope(), request.target());
    }

    private static Optional<KickDTO.Error> validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return error(ErrorKind.VALIDATION, "a reason is required");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            return error(ErrorKind.VALIDATION, "the reason may have at most " + MAX_REASON_LENGTH + " characters");
        }
        if (PiiGuard.containsUuid(reason) || PiiGuard.containsIp(reason) || !PiiGuard.banIdCandidates(reason).isEmpty()) {
            return error(ErrorKind.VALIDATION, "the reason must not contain a UUID, an IP address or a Ban ID");
        }
        return Optional.empty();
    }

    private static Optional<KickDTO.Error> validateTarget(KickScope scope, String target) {
        boolean hasTarget = target != null && !target.isBlank();
        return switch (scope) {
            case NETWORK -> hasTarget
                    ? error(ErrorKind.KICK_TARGET_INVALID, "a network kick has no target")
                    : Optional.empty();
            case SERVER -> hasTarget && SERVER_NAME.matcher(target).matches()
                    ? Optional.empty()
                    : error(ErrorKind.KICK_TARGET_INVALID, "a server kick needs the name of a server as target");
            case SECTION -> hasTarget && SECTION_KEY.matcher(target).matches()
                    ? Optional.empty()
                    : error(ErrorKind.KICK_TARGET_INVALID, "a section kick needs the key of a section as target");
        };
    }

    private static Optional<KickDTO.Error> error(ErrorKind kind, String message) {
        return Optional.of(new KickDTO.Error(message, kind));
    }
}
