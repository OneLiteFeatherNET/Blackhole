package net.onelitefeather.blackhole.backend.exemption;

import net.onelitefeather.blackhole.backend.exemption.dto.ExemptionDTO;
import net.onelitefeather.blackhole.backend.support.StaffLinkTestBed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExemptionProjectionTest {

    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    private static final String OWNER = ExemptionService.ownerOf(PLAYER);

    private final StaffLinkTestBed bed = new StaffLinkTestBed(
            new net.onelitefeather.blackhole.backend.events.DomainEventPublisher(
                    new net.onelitefeather.blackhole.backend.support.RecordingEventTransport(),
                    new net.onelitefeather.blackhole.backend.support.TestClock()),
            new net.onelitefeather.blackhole.backend.support.TestClock());

    @Test
    void aLinkWritesALinkedStaffRowKeyedByTheOwnerNotTheUuid() {
        bed.exemptions.linkStaff(OWNER, STAFF);

        assertEquals(1, bed.exemptionRows.all().size(), "one linked-staff row");
        var row = bed.exemptionRows.all().getFirst();
        assertEquals(OWNER, row.getId().getOwner(), "keyed by the hashed owner");
        assertEquals("LINKED_STAFF", row.getId().getSource());
        assertEquals(STAFF, row.getStaffOid());
        assertFalse(row.getId().getOwner().contains(PLAYER.toString()), "no raw UUID is stored in the projection");
    }

    @Test
    void anUnlinkDeletesTheLinkedStaffRow() {
        bed.exemptions.linkStaff(OWNER, STAFF);

        bed.exemptions.unlinkStaff(OWNER);

        assertTrue(bed.exemptionRows.all().isEmpty(), "the row ends with the link");
    }

    @Test
    void aPermissionReportReplacesThePermissionRowOfTheOwner() {
        bed.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of("survival")));
        bed.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of("lobby")));

        assertEquals(1, bed.exemptionRows.all().size(), "one permission row per owner");
        assertEquals("lobby", bed.exemptionRows.all().getFirst().getSections(), "the later report replaces the sections");
    }

    @Test
    void aReportWithoutFlagsRemovesThePermissionRow() {
        bed.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of("*")));

        bed.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of()));

        assertTrue(bed.exemptionRows.all().isEmpty(), "no flag means no permission exemption");
    }

    @Test
    void aPermissionReportLeavesTheLinkedStaffRowAlone() {
        bed.exemptions.linkStaff(OWNER, STAFF);

        bed.exemptions.report(new ExemptionDTO.ReportRequest(OWNER, List.of()));

        assertEquals(1, bed.exemptionRows.all().size(), "the link is not a permission fact");
        assertEquals("LINKED_STAFF", bed.exemptionRows.all().getFirst().getId().getSource());
    }

    @Test
    void aReportForAnInvalidOwnerIsRefusedAndWritesNothing() {
        ExemptionDTO result = bed.exemptions.report(new ExemptionDTO.ReportRequest("not-a-hash", List.of("*")));

        assertTrue(result instanceof ExemptionDTO.Error, "an owner that is not a SHA-512 hash is refused");
        assertTrue(bed.exemptionRows.all().isEmpty(), "nothing is written for a refused report");
    }
}
