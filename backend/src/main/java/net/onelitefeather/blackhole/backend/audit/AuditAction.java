package net.onelitefeather.blackhole.backend.audit;

/** The fixed vocabulary of audited staff actions. The wire name is what the table and the event carry. */
public enum AuditAction {
    PUNISHMENT_APPLY("punishment.apply"),
    PUNISHMENT_REVOKE("punishment.revoke"),
    CASE_UPDATE("case.update"),
    CASE_NOTE_ADD("case.note.add"),
    CASE_NOTE_EDIT("case.note.edit"),
    CASE_NOTE_REDACT("case.note.redact"),
    APPEAL_REVIEW("appeal.review"),
    REPORT_RESOLVE("report.resolve"),
    EVIDENCE_ATTACH("evidence.attach"),
    EVIDENCE_DELETE("evidence.delete"),
    ELO_ADJUST("elo.adjust"),
    KICK_CREATE("kick.create"),
    KICK_MASS("kick.mass"),
    KICK_ROLLBACK_MARK("kick.rollback-mark"),
    STAFF_LINK_UNLINK("staff-link.unlink"),
    DISCORD_LINK_REMOVE("discord-link.remove"),
    CLIENT_RULE_CREATE("client-rule.create"),
    CLIENT_RULE_UPDATE("client-rule.update"),
    CLIENT_RULE_DELETE("client-rule.delete"),
    SECTION_CREATE("section.create"),
    SECTION_UPDATE("section.update"),
    SECTION_DISABLE("section.disable"),
    TEMPLATE_CREATE("template.create"),
    TEMPLATE_UPDATE("template.update"),
    TEMPLATE_REMOVE("template.remove"),
    IMPORT_RETEMPLATE("import.retemplate"),
    IMPORT_VANILLA("import.vanilla"),
    TICKET_CLOSE("ticket.close"),
    TICKET_FREEZE("ticket.freeze"),
    INTEL_POLICY_UPDATE("intel.policy.update"),
    INTEL_ALLOW_LIST_ADD("intel.allow-list.add"),
    INTEL_ALLOW_LIST_REMOVE("intel.allow-list.remove"),
    GUARD_LEVEL_SET("guard.level.set"),
    AREA_BAN_CREATE("area-ban.create"),
    AREA_BAN_LIFT("area-ban.lift"),
    GUARD_SETTINGS_SET("guard.settings.set");

    private final String wireName;

    AuditAction(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return this.wireName;
    }
}
