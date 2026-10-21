package net.onelitefeather.blackhole.backend.punishment;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Duration;

/**
 * One rung of a template's escalation ladder: how long the punishment lasts and how much ELO it
 * costs. The duration is kept as the ISO-8601 text it was submitted as.
 */
@Embeddable
public class TemplateStep {

    @Column(name = "duration")
    private String duration;

    @Column(name = "elo_cost")
    private int eloCost;

    public TemplateStep() {
        // Empty constructor for JPA
    }

    public TemplateStep(String duration, int eloCost) {
        this.duration = duration;
        this.eloCost = eloCost;
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
