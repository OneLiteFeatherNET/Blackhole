package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionValidator;
import net.onelitefeather.blackhole.backend.section.ServerPatterns;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business rules for a client rule, independent of how it is stored. Whether the named sections and
 * the template exist is not checked here; that needs the registries and lives in the service.
 */
public final class ClientRuleValidator {

    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;
    public static final int MAX_PATTERNS = 32;
    public static final int MAX_PATTERN_LENGTH = 128;
    public static final int MAX_DENY_MESSAGE_LENGTH = 200;
    public static final int MAX_SECTION_KEYS = 32;

    /** Words a key must not be, because they are routes under {@code /client-rule}. */
    private static final Set<String> RESERVED_KEYS = Set.of("catalogue", "hits", "hit");

    private static final Pattern KEY = Pattern.compile("[a-z0-9-]+");

    /** The glob alphabet: no regular expression, no whitespace, no control character. */
    private static final Pattern PATTERN_CHARACTERS = Pattern.compile("[a-z0-9._:*?/@+-]+");

    /**
     * @return one message per violated rule, empty if {@code spec} is valid
     */
    public static List<String> validate(ClientRuleSpec spec) {
        List<String> errors = new ArrayList<>();
        validateIdentity(spec, errors);
        if (spec.kind() == null) {
            errors.add("kind is required");
            return errors;
        }
        validateMatch(spec, errors);
        validateScope(spec, errors);
        validateAction(spec, errors);
        return errors;
    }

    /**
     * Whether a rule of this kind and action must carry the acknowledgement that the signal can be
     * faked: a punishment on something the client chooses.
     */
    public static boolean requiresAcknowledgement(ClientRuleKind kind, ClientRuleAction action) {
        return action == ClientRuleAction.TEMPLATE && kind.spoofable();
    }

    private static void validateIdentity(ClientRuleSpec spec, List<String> errors) {
        String key = spec.key();
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
            errors.add("key must be 1 to " + MAX_KEY_LENGTH + " characters of a-z, 0-9 and '-'");
        } else if (RESERVED_KEYS.contains(key)) {
            errors.add("key '" + key + "' is reserved");
        }
        if (spec.displayName() == null || spec.displayName().isBlank() || spec.displayName().length() > MAX_DISPLAY_NAME_LENGTH) {
            errors.add("displayName must be 1 to " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
    }

    private static void validateMatch(ClientRuleSpec spec, List<String> errors) {
        ClientRuleKind kind = spec.kind();
        if (kind.usesPatterns()) {
            validatePatterns(spec.patterns(), errors);
            if (spec.protocolMin() != null || spec.protocolMax() != null) {
                errors.add("protocolMin and protocolMax are only allowed for PROTOCOL rules");
            }
        } else {
            if (!spec.patterns().isEmpty()) {
                errors.add("patterns are not allowed for PROTOCOL rules");
            }
            validateRange(spec, errors);
        }
        if (spec.negate() && kind != ClientRuleKind.BRAND && kind != ClientRuleKind.PROTOCOL) {
            errors.add("negate is only allowed for BRAND and PROTOCOL rules");
        }
        if (spec.hideCommand() && kind != ClientRuleKind.COMMAND) {
            errors.add("hideCommand is only allowed for COMMAND rules");
        }
    }

    private static void validatePatterns(List<String> patterns, List<String> errors) {
        if (patterns.isEmpty() || patterns.size() > MAX_PATTERNS) {
            errors.add("patterns must hold 1 to " + MAX_PATTERNS + " entries");
        }
        for (String pattern : patterns) {
            if (pattern.isEmpty() || pattern.length() > MAX_PATTERN_LENGTH) {
                errors.add("patterns: each pattern must be 1 to " + MAX_PATTERN_LENGTH + " characters");
            } else if (!PATTERN_CHARACTERS.matcher(pattern).matches()) {
                errors.add("patterns: '" + shown(pattern) + "' may only contain a-z, 0-9 and . _ : * ? / @ + - (no regular expressions)");
            } else if (pattern.chars().allMatch(c -> c == '*' || c == '?')) {
                errors.add("patterns: '" + pattern + "' would match everything; add at least one literal character");
            }
        }
    }

    private static void validateRange(ClientRuleSpec spec, List<String> errors) {
        Integer min = spec.protocolMin();
        Integer max = spec.protocolMax();
        if (min == null || max == null) {
            errors.add("protocolMin and protocolMax are required for PROTOCOL rules");
        } else if (min < 0 || max < 0) {
            errors.add("protocolMin and protocolMax must not be negative");
        } else if (min > max) {
            errors.add("protocolMin must not be above protocolMax");
        }
    }

    private static void validateScope(ClientRuleSpec spec, List<String> errors) {
        boolean hasSections = !spec.sectionKeys().isEmpty();
        boolean hasServers = !spec.servers().isEmpty();
        if (spec.scope() == ClientRuleScope.GLOBAL) {
            if (hasSections || hasServers) {
                errors.add("scope GLOBAL must not list sectionKeys or servers");
            }
            return;
        }
        if (!hasSections && !hasServers) {
            errors.add("scope SERVERS needs at least one section key or server");
        }
        if (spec.sectionKeys().size() > MAX_SECTION_KEYS) {
            errors.add("sectionKeys may list at most " + MAX_SECTION_KEYS + " sections");
        }
        for (String sectionKey : spec.sectionKeys()) {
            if (sectionKey == null || sectionKey.length() > MAX_KEY_LENGTH || !SectionValidator.KEBAB_CASE.matcher(sectionKey).matches()) {
                errors.add("sectionKeys: '" + shown(sectionKey) + "' is not a section key");
            }
        }
        if (hasServers) {
            ServerPatterns.validate(EnforcementKind.SERVER, spec.targetKind(), spec.servers()).forEach(problem -> errors.add("servers: " + problem));
        }
    }

    private static void validateAction(ClientRuleSpec spec, List<String> errors) {
        if (spec.action() == ClientRuleAction.DENY) {
            validateDenyMessage(spec.denyMessage(), errors);
        } else if (spec.denyMessage() != null) {
            errors.add("denyMessage is only allowed for the DENY action");
        }
        if (spec.action() == ClientRuleAction.TEMPLATE) {
            if (spec.templateKey() == null) {
                errors.add("templateKey is required for the TEMPLATE action");
            }
            if (requiresAcknowledgement(spec.kind(), spec.action()) && !spec.acknowledgedSpoofable()) {
                errors.add("acknowledgedSpoofable must be true: the client controls this signal and can fake it, so a hit is evidence, not proof");
            }
        } else if (spec.templateKey() != null) {
            errors.add("templateKey is only allowed for the TEMPLATE action");
        }
    }

    private static void validateDenyMessage(String message, List<String> errors) {
        if (message == null) {
            return;
        }
        if (message.length() > MAX_DENY_MESSAGE_LENGTH) {
            errors.add("denyMessage must have at most " + MAX_DENY_MESSAGE_LENGTH + " characters");
            return;
        }
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '§') {
                errors.add("denyMessage must be plain text: no control characters and no formatting codes");
                return;
            }
        }
    }

    private static String shown(String value) {
        if (value == null) {
            return "null";
        }
        String cut = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return cut.replaceAll("\\p{Cntrl}", "?");
    }

    private ClientRuleValidator() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
