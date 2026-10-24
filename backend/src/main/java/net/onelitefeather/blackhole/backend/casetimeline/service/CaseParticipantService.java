package net.onelitefeather.blackhole.backend.casetimeline.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseErrorKind;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseParticipantDTO;
import net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolRedaction;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * Erasure of conversation participants (Discord users). The Discord user id and the display name are removed and {@code erasedAt} is set; the
 * timeline keeps its structure with an anonymous author. The stored protocols are purged and regenerated ({@link ProtocolRedaction}). Message
 * text is redacted by the conversation routes, not here.
 */
@Singleton
public class CaseParticipantService implements ParticipantErasure {

    private final CaseReference references;
    private final DiscordTicketStore store;
    private final ProtocolRedaction protocols;
    private final Clock clock;

    public CaseParticipantService(PunishmentRepository punishments, DiscordTicketStore store, ProtocolRedaction protocols, Clock clock) {
        this.references = new CaseReference(punishments);
        this.store = store;
        this.protocols = protocols;
        this.clock = clock;
    }

    /**
     * Erases one participant of a case; erasing an already erased one changes nothing.
     *
     * @return {@link CaseParticipantDTO.Erased}, or an error: 404 unknown punishment or participant, 409 ambiguous short id
     */
    public CaseParticipantDTO erase(String reference, UUID participantId) {
        CaseReference.Found found = this.references.resolve(reference);
        if (found.failed()) {
            return new CaseParticipantDTO.Error(found.message(), found.kind());
        }
        String id = found.punishment().getIdentifier();
        if (this.store.participant(participantId).filter(participant -> participant.punishmentIdentifier().equals(id)).isEmpty()) {
            return new CaseParticipantDTO.Error("there is no such participant", CaseErrorKind.NOT_FOUND);
        }
        boolean erased = this.store.eraseParticipant(id, participantId, this.clock.millis());
        if (erased) {
            this.protocols.afterParticipantErased(id);
        }
        return new CaseParticipantDTO.Erased(erased);
    }

    @Override
    public int erasePlayerParticipants(String punishmentIdentifier) {
        int erased = this.store.erasePlayerParticipants(punishmentIdentifier, this.clock.millis());
        if (erased > 0) {
            this.protocols.afterParticipantErased(punishmentIdentifier);
        }
        return erased;
    }
}
