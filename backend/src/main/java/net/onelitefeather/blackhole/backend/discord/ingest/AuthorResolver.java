package net.onelitefeather.blackhole.backend.discord.ingest;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;

import java.util.UUID;

/**
 * Who wrote a Discord message, judged at ingest time from what the bot reports and the links as they are then; a later role or link change
 * never rewrites history. The rules are the author table of the design:
 * <ol>
 *     <li>another bot: {@code BOT}, system trust, staff visibility (kept in the record, not shown to the player);</li>
 *     <li>the ticket's bound user: the player, trust {@code PLAYER};</li>
 *     <li>the staff role <b>and</b> a live staff link: {@code STAFF}, {@code VERIFIED}, the linked {@code oid};</li>
 *     <li>the staff role without a link: {@code STAFF}, {@code UNVERIFIED}, a participant;</li>
 *     <li>anyone else: {@code EXTERNAL}, {@code UNVERIFIED}.</li>
 * </ol>
 */
public final class AuthorResolver {

    /**
     * @param participantKind what to record the author as in {@code case_participant}, {@code null} for a verified staff member (the link
     *                        identifies them; no Discord id is stored for the message)
     * @param staffOid        the linked {@code oid} of a verified staff member
     * @param boundPlayer     whether the author is the ticket's bound player (the participant is the ticket's own)
     * @param visibility      {@code PLAYER} or {@code STAFF}
     */
    public record Resolution(MessageAuthorKind kind, ActorTrust trust, @Nullable ParticipantKind participantKind, @Nullable UUID staffOid,
                             boolean boundPlayer, String visibility) {
    }

    private AuthorResolver() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param authorDiscordUserId the author's Discord user id
     * @param bot                 whether Discord marks the author a bot
     * @param hasStaffRole        whether the author held the staff role when writing
     * @param boundDiscordUserId  the Discord user id of the ticket's bound participant, {@code null} once it was erased
     * @param linkedStaffOid      the {@code oid} of the live staff link of the author, {@code null} when there is none
     */
    public static Resolution resolve(String authorDiscordUserId, boolean bot, boolean hasStaffRole, @Nullable String boundDiscordUserId,
                                     @Nullable UUID linkedStaffOid) {
        if (bot) {
            return new Resolution(MessageAuthorKind.BOT, ActorTrust.SYSTEM, ParticipantKind.BOT, null, false, CaseEventDraft.STAFF);
        }
        if (boundDiscordUserId != null && boundDiscordUserId.equals(authorDiscordUserId)) {
            return new Resolution(MessageAuthorKind.PLAYER, ActorTrust.PLAYER, ParticipantKind.PLAYER, null, true, CaseEventDraft.PLAYER);
        }
        if (hasStaffRole && linkedStaffOid != null) {
            return new Resolution(MessageAuthorKind.STAFF, ActorTrust.VERIFIED, null, linkedStaffOid, false, CaseEventDraft.PLAYER);
        }
        if (hasStaffRole) {
            return new Resolution(MessageAuthorKind.STAFF, ActorTrust.UNVERIFIED, ParticipantKind.STAFF, null, false, CaseEventDraft.PLAYER);
        }
        return new Resolution(MessageAuthorKind.EXTERNAL, ActorTrust.UNVERIFIED, ParticipantKind.EXTERNAL, null, false, CaseEventDraft.PLAYER);
    }
}
