package net.onelitefeather.blackhole.backend.audit;

import io.micronaut.core.annotation.Nullable;

/**
 * What a staff action was about. A case is named by its short id, a player by its owner hash, a section or
 * server by its key. Never a Ban ID, an IP address or a raw UUID.
 *
 * @param kind the target kind, one of {@code CASE}, {@code PLAYER}, {@code SECTION}, {@code SERVER}, {@code CLIENT_RULE},
 *             {@code TEMPLATE}, {@code AREA_BAN}, {@code NONE}
 * @param ref  the case short id, owner hash, section key or server name
 */
public record AuditTarget(String kind, @Nullable String ref) {

    public static AuditTarget ofCase(String shortId) {
        return new AuditTarget("CASE", shortId);
    }

    public static AuditTarget ofPlayer(String ownerHash) {
        return new AuditTarget("PLAYER", ownerHash);
    }

    public static AuditTarget ofSection(String key) {
        return new AuditTarget("SECTION", key);
    }

    public static AuditTarget ofServer(String name) {
        return new AuditTarget("SERVER", name);
    }

    public static AuditTarget ofClientRule(String key) {
        return new AuditTarget("CLIENT_RULE", key);
    }

    public static AuditTarget ofTemplate(String key) {
        return new AuditTarget("TEMPLATE", key);
    }

    /** An area ban is named by its area: the ASN number or the country code, never an address. */
    public static AuditTarget ofAreaBan(String area) {
        return new AuditTarget("AREA_BAN", area);
    }

    public static AuditTarget none() {
        return new AuditTarget("NONE", null);
    }

    /** The team panel page of the target, or {@code null} when there is none. */
    @Nullable
    public String panelPath() {
        return panelPathOf(this.kind, this.ref);
    }

    /** The team panel page of a stored target, or {@code null} when there is none. */
    @Nullable
    public static String panelPathOf(String kind, @Nullable String ref) {
        return switch (kind) {
            case "CASE" -> "/cases/" + ref;
            case "PLAYER" -> "/players/" + ref;
            case "CLIENT_RULE" -> "/client-rules";
            default -> null;
        };
    }
}
