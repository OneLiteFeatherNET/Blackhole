package net.onelitefeather.blackhole.backend.section;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A punishment section: owns a player's ELO score, its thresholds, and how its punishments are
 * enforced. See the {@code punishment-sections} capability.
 */
@Entity
@Table(name = "punishment_sections")
public class SectionEntity {

    @Id
    @Column(name = "section_key")
    private String sectionKey;

    private String displayName;

    @Enumerated(EnumType.STRING)
    private EnforcementKind enforcement;

    @Enumerated(EnumType.STRING)
    private ServerTargetKind targetKind = ServerTargetKind.PATTERN;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "punishment_section_servers", joinColumns = @JoinColumn(name = "section_key"))
    @Column(name = "pattern")
    private Set<String> servers = new LinkedHashSet<>();

    private int baseScore;

    private Integer softThreshold;

    private String softDuration;

    private int permanentThreshold;

    private int pardonScore;

    private String permanentReason;

    private int decayPerDay;

    private boolean enabled;

    public SectionEntity() {
        // Empty constructor for JPA
    }

    public SectionEntity(SectionSpec spec, boolean enabled) {
        this.sectionKey = spec.key();
        this.enabled = enabled;
        apply(spec);
    }

    /**
     * Overwrites every editable field with the values of {@code spec} (the key never changes).
     */
    public void apply(SectionSpec spec) {
        this.displayName = spec.displayName();
        this.enforcement = spec.enforcement();
        this.targetKind = spec.targetKind();
        this.servers.clear();
        if (spec.servers() != null) {
            this.servers.addAll(spec.servers());
        }
        this.baseScore = spec.baseScore();
        this.softThreshold = spec.softThreshold();
        this.softDuration = spec.softThreshold() == null ? null : spec.softDuration();
        this.permanentThreshold = spec.permanentThreshold();
        this.pardonScore = spec.pardonScore();
        this.permanentReason = spec.permanentReason();
        this.decayPerDay = spec.decayPerDay();
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SectionDTO.Response toResponse() {
        List<String> sortedServers = this.servers.stream().sorted().toList();
        return new SectionDTO.Response(
                this.sectionKey, this.displayName, this.enforcement, this.targetKind, sortedServers, this.baseScore,
                this.softThreshold, this.softDuration, this.permanentThreshold, this.pardonScore,
                this.permanentReason, this.decayPerDay, this.enabled
        );
    }
}
