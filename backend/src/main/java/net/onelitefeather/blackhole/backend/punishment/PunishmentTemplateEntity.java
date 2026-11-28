package net.onelitefeather.blackhole.backend.punishment;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.database.converter.MapStringObjectConverter;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A staff punishment template: always temporary, tied to a section, with an ordered ladder of
 * escalation steps. The legacy {@code type} and {@code elo_delta} columns are no longer mapped.
 */
@Entity
@Table(name = "punishment_templates", indexes = {@Index(columnList = "identifier")})
public class PunishmentTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID identifier;

    @Column(name = "template_key")
    private String templateKey;

    private String reason;

    @Column(name = "section_key")
    private String sectionKey;

    private String escalationGroup;

    private String escalationWindow;

    /** ISO-8601 window for warnings only; {@code null} means warnings count within the escalation window. */
    @Column(name = "warn_window", length = 32)
    private String warnWindow;

    /** The text the public ban list shows for punishments of this template; {@code null} means not listed. */
    @Column(name = "public_reason", length = 200)
    private String publicReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "punishment_template_steps", joinColumns = @JoinColumn(name = "template_identifier"))
    @OrderColumn(name = "step_index")
    private List<TemplateStep> steps = new ArrayList<>();

    @Convert(converter = MapStringObjectConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metaData = new HashMap<>();

    /**
     * Rebuilds the entity from its response form, for callers that submit whole profiles.
     */
    public static PunishmentTemplateEntity toEntity(PunishTemplateDTO.Response template) {
        PunishmentTemplateEntity entity = new PunishmentTemplateEntity(
                template.identifier(), template.key(), template.reason(), template.section(), template.escalationGroup(),
                template.escalationWindow(),
                template.steps().stream().map(step -> new TemplateStep(step.kind(), step.duration(), step.eloCost())).toList(),
                template.metaData()
        );
        entity.warnWindow = template.warnWindow();
        entity.publicReason = template.publicReason();
        return entity;
    }

    public PunishmentTemplateEntity() {
        // Empty constructor for JPA
    }

    public PunishmentTemplateEntity(
            UUID identifier,
            String templateKey,
            String reason,
            String sectionKey,
            String escalationGroup,
            String escalationWindow,
            List<TemplateStep> steps,
            Map<String, Object> metaData
    ) {
        this.identifier = identifier;
        this.templateKey = templateKey;
        this.reason = reason;
        this.sectionKey = sectionKey;
        this.escalationGroup = escalationGroup;
        this.escalationWindow = escalationWindow;
        this.steps = new ArrayList<>(steps);
        this.metaData = metaData;
    }

    public UUID getIdentifier() {
        return identifier;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getReason() {
        return reason;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public String getEscalationGroup() {
        return escalationGroup;
    }

    /** The ISO-8601 escalation window, or {@code null} for the configured default. */
    public String getEscalationWindow() {
        return escalationWindow;
    }

    /** The ISO-8601 warn window, or {@code null} when warnings count within the escalation window. */
    public String getWarnWindow() {
        return warnWindow;
    }

    public void setWarnWindow(String warnWindow) {
        this.warnWindow = warnWindow;
    }

    /** The public reason, or {@code null} when punishments of this template are not listed. */
    public String getPublicReason() {
        return publicReason;
    }

    public void setPublicReason(String publicReason) {
        this.publicReason = publicReason;
    }

    public List<TemplateStep> getSteps() {
        return steps;
    }

    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public PunishTemplateDTO.Response toDTO() {
        return new PunishTemplateDTO.Response(
                this.identifier,
                this.templateKey,
                this.reason,
                this.sectionKey,
                this.escalationGroup,
                this.escalationWindow,
                this.warnWindow,
                this.steps.stream().map(step -> new PunishTemplateDTO.Step(step.getDuration(), step.getEloCost(), step.getKind())).toList(),
                this.metaData,
                this.publicReason
        );
    }
}
