package net.onelitefeather.blackhole.backend.punishment;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Duration;

/**
 * One rung of a template's escalation ladder: how long the punishment lasts and how much ELO it
 * costs. The duration is kept as the ISO-8601 text it was submitted as.
 */
@Embeddable
public class TemplateStep {

    /** {@code null} for a {@link StepKind#KICK} step. */
    @Column(name = "duration")
    private String duration;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private StepKind kind = StepKind.PUNISH;

    @Column(name = "elo_cost")
    private int eloCost;

    public TemplateStep() {
        // Empty constructor for JPA
    }

    public TemplateStep(String duration, int eloCost) {
        this(StepKind.PUNISH, duration, eloCost);
    }

    public TemplateStep(StepKind kind, String duration, int eloCost) {
        this.kind = kind == null ? StepKind.PUNISH : kind;
        this.duration = duration;
        this.eloCost = eloCost;
    }

    public StepKind getKind() {
        return kind;
    }

    /** The ISO-8601 duration text. */
    public String getDuration() {
        return duration;
    }

    public int getEloCost() {
        return eloCost;
    }

    /** The duration parsed; only valid steps are ever stored. */
    public Duration parsedDuration() {
        return Duration.parse(this.duration);
    }
}
