package net.onelitefeather.blackhole.backend.exemption;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One exemption fact about a player, keyed by the hashed owner: a {@code LINKED_STAFF} row while the Minecraft account is
 * linked to a staff member, or a {@code PERMISSION} row with the sections the proxy last reported. Never holds a raw UUID.
 * Deliberately not {@code @Serdeable}: entities never leave the backend.
 */
@Entity
@Table(name = "player_exemption")
public class PlayerExemptionEntity {

    @EmbeddedId
    private PlayerExemptionId id;

    /** Comma-separated section keys of a PERMISSION row, {@code *} for all sections; null for LINKED_STAFF. */
    @Column(name = "sections")
    private String sections;

    @Column(name = "staff_oid")
    private UUID staffOid;

    @Column(name = "reported_at")
    private long reportedAt;

    public PlayerExemptionEntity() {
        // Empty constructor for JPA
    }

    public PlayerExemptionEntity(PlayerExemptionId id, String sections, UUID staffOid, long reportedAt) {
        this.id = id;
        this.sections = sections;
        this.staffOid = staffOid;
        this.reportedAt = reportedAt;
    }

    public PlayerExemptionId getId() {
        return id;
    }

    public String getSections() {
        return sections;
    }

    public UUID getStaffOid() {
        return staffOid;
    }

    public long getReportedAt() {
        return reportedAt;
    }

    public void setSections(String sections) {
        this.sections = sections;
    }

    public void setStaffOid(UUID staffOid) {
        this.staffOid = staffOid;
    }

    public void setReportedAt(long reportedAt) {
        this.reportedAt = reportedAt;
    }
}
