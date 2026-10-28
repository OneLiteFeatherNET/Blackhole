package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;

import java.util.List;
import java.util.Map;

/**
 * The editable fields of a template as one value, so validation does not depend on whether a create
 * or an update request carried them.
 */
public record TemplateSpec(
        String key,
        String reason,
        String section,
        String escalationGroup,
        String escalationWindow,
        String warnWindow,
        List<PunishTemplateDTO.Step> steps,
        Map<String, Object> metaData
) {

    /** A spec without a warn window: warnings count within the escalation window. */
    public TemplateSpec(String key, String reason, String section, String escalationGroup, String escalationWindow,
                        List<PunishTemplateDTO.Step> steps, Map<String, Object> metaData) {
        this(key, reason, section, escalationGroup, escalationWindow, null, steps, metaData);
    }

    public static TemplateSpec of(PunishTemplateDTO.CreateRequest request) {
        return new TemplateSpec(
                request.key(), request.reason(), request.section(), request.escalationGroup(), request.escalationWindow(),
                request.warnWindow(), request.steps(), request.metaData()
        );
    }

    public static TemplateSpec of(PunishTemplateDTO.UpdateRequest request) {
        return new TemplateSpec(
                request.key(), request.reason(), request.section(), request.escalationGroup(), request.escalationWindow(),
                request.warnWindow(), request.steps(), request.metaData()
        );
    }

    /** The escalation group, defaulting to the template key. */
    public String effectiveGroup() {
        return this.escalationGroup == null || this.escalationGroup.isBlank() ? this.key : this.escalationGroup;
    }
}
