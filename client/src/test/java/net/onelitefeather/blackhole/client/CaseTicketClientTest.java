package net.onelitefeather.blackhole.client;

import net.onelitefeather.blackhole.client.model.CaseResponse;
import net.onelitefeather.blackhole.client.model.CaseTicket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The generated client of spec 0.0.50 exposes the appeal ticket state on the staff case. */
class CaseTicketClientTest {

    @Test
    void theCaseResponseCarriesTheTicketStateOfTheCase() {
        CaseResponse response = new CaseResponse().ticket(new CaseTicket().state("OPEN").frozen(true).freezeSource("STAFF"));

        assertEquals("OPEN", response.getTicket().getState(), "the ticket state is read from the case");
        assertEquals(true, response.getTicket().getFrozen(), "a lock made in Discord is visible as frozen");
        assertEquals("STAFF", response.getTicket().getFreezeSource(), "the lock names its source");
    }

    @Test
    void aCaseWithoutATicketHasNoTicketState() {
        assertNull(new CaseResponse().getTicket(), "the ticket is optional: a case that never had an appeal ticket has none");
    }
}
