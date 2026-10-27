package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.APPEAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The author labels of the stored protocol: a named message shows its snapshot, a legacy one keeps the old labels. Pure. */
class ProtocolModelTest {

    private static ProtocolSource.Appeal decidedAppeal() {
        return ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, 1_000, 4_000);
    }

    /** A staff message written under {@code snapshot} (payload version 2) when it is non-null, otherwise a legacy message. */
    private static CaseReader.Entry staffMessage(long seq, String snapshot, String liveName) {
        CaseEventPayload.MessageAdded added = new CaseEventPayload.MessageAdded("m-" + seq, APPEAL.toString(), MessageAuthorKind.STAFF, null,
                MessageChannel.PANEL, "We looked at the logs.", List.of(), List.of(), snapshot);
        return ProtocolFixtures.entry(ProtocolFixtures.event(seq, seq * 1_000, "message.added", ActorTrust.VERIFIED, "m-" + seq, "PLAYER",
                added.toMap()), liveName);
    }

    private static ProtocolModel build(CaseReader.Entry... entries) {
        List<CaseReader.Entry> all = new java.util.ArrayList<>(List.of(ProtocolFixtures.filed(1, 1_000, APPEAL)));
        all.addAll(List.of(entries));
        all.add(ProtocolFixtures.decided(99, 4_000, APPEAL, "DENIED"));
        return ProtocolModel.build(decidedAppeal(), List.of(decidedAppeal()), all);
    }

    private static String labelOf(ProtocolModel model, long seq) {
        return model.entries().stream().filter(entry -> entry.seq() == seq).findFirst()
                .map(entry -> entry.author().label()).orElseThrow();
    }

    @Test
    void aNamedStaffMessageShowsTheSnapshotNotTheLiveName() {
        ProtocolModel model = build(staffMessage(2, "Mia K.", "Renamed Person"));

        assertEquals("Mia K.", labelOf(model, 2), "the name the staff member had when writing, even after a rename");
        assertEquals("VERIFIED", assertInstanceOf(ProtocolModel.MessageEntry.class, model.entries().get(1)).author().trust());
    }

    @Test
    void aLegacyStaffMessageKeepsTheLiveActorName() {
        ProtocolModel model = build(staffMessage(2, null, "Reviewer Rita"));

        assertEquals("Reviewer Rita", labelOf(model, 2), "version 1 is unchanged: the stored label is the actor's name");
    }

    @Test
    void aLegacyStaffMessageWithoutAnyNameIsAnonymous() {
        ProtocolModel model = build(staffMessage(2, null, null));

        assertEquals(ProtocolModel.ANONYMOUS_STAFF_LABEL, labelOf(model, 2));
    }

    @Test
    void aMessageWhoseSnapshotWasClearedAndWhoseActorIsGoneIsAnonymous() {
        ProtocolModel model = build(staffMessage(2, null, null));

        assertEquals(ProtocolModel.ANONYMOUS_STAFF_LABEL, labelOf(model, 2), "an anonymised author shows no name");
    }

    @Test
    void theDecisionEventShowsTheReviewersLiveName() {
        ProtocolModel model = build();

        assertEquals("Reviewer Rita", labelOf(model, 99), "a lifecycle marker by a verified staff member uses the actor's name");
    }

    @Test
    void theVerifiedStaffLabelIsTheNameOrAnonymousAndNeverBlank() {
        assertEquals("Mia K.", ProtocolModel.authorLabel(MessageAuthorKind.STAFF, ActorTrust.VERIFIED, "Mia K."));
        assertEquals(ProtocolModel.ANONYMOUS_STAFF_LABEL, ProtocolModel.authorLabel(MessageAuthorKind.STAFF, ActorTrust.VERIFIED, null));
        assertEquals(ProtocolModel.ANONYMOUS_STAFF_LABEL, ProtocolModel.authorLabel(MessageAuthorKind.STAFF, ActorTrust.VERIFIED, "  "));
    }

    @Test
    void theOtherLabelsAreUnchanged() {
        assertEquals("Player", ProtocolModel.authorLabel(MessageAuthorKind.PLAYER, ActorTrust.PLAYER, "Mia K."));
        assertEquals("Bot", ProtocolModel.authorLabel(MessageAuthorKind.BOT, ActorTrust.SYSTEM, null));
        assertEquals("External participant", ProtocolModel.authorLabel(MessageAuthorKind.EXTERNAL, ActorTrust.UNVERIFIED, null));
        assertEquals("Unverified staff", ProtocolModel.authorLabel(MessageAuthorKind.STAFF, ActorTrust.UNVERIFIED, "Mia K."));
    }
}
