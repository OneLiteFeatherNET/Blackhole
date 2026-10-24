package net.onelitefeather.blackhole.backend.discord.leak;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.BanIdCodec;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakReason;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPublisher;
import net.onelitefeather.blackhole.backend.discord.DiscordTicketProperties;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLeakCheckDTO;
import net.onelitefeather.blackhole.backend.discord.ticket.DiscordTicketStore;
import net.onelitefeather.blackhole.backend.discord.ticket.Participant;
import net.onelitefeather.blackhole.backend.discord.ticket.ParticipantKind;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verifies what the Discord bot found in a public channel. A candidate that is a <b>current</b> Ban ID marks the case leaked with reason
 * {@code POSTED_PUBLIC} and source {@code DISCORD_BOT} through the same single-statement change as a staff mark ({@link CaseChanges}), naming the
 * poster only as an opaque participant and whether that was the ticket's bound player. A candidate that is no Ban ID, or a retired one, causes
 * nothing: no event, no row, no log line.
 *
 * <p>Neither the candidates nor the poster's Discord id are logged, counted or put in an event. The sessions of the player portal are deleted
 * with the leak once the public API stores them; until then there is nothing to delete.</p>
 */
@Singleton
public class LeakCheckService {

    private static final Pattern USER_ID = Pattern.compile("^\\d{15,20}$");

    private final BanIdService banIds;
    private final PunishmentRepository punishments;
    private final DiscordTicketStore tickets;
    private final CaseChanges changes;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final DiscordTicketProperties properties;

    public LeakCheckService(
            BanIdService banIds, PunishmentRepository punishments, DiscordTicketStore tickets, CaseChanges changes, DomainEventPublisher events,
            Clock clock, DiscordTicketProperties properties
    ) {
        this.banIds = banIds;
        this.punishments = punishments;
        this.tickets = tickets;
        this.changes = changes;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * @return {@link DiscordLeakCheckDTO.Result}, or 400 for a malformed request
     */
    public DiscordLeakCheckDTO check(DiscordLeakCheckDTO.Request request, Actor bot) {
        if (request.candidates() == null || request.channelKind() == null) {
            return new DiscordLeakCheckDTO.Error("candidates and channelKind are required", ErrorKind.VALIDATION);
        }
        String poster = request.posterDiscordUserId();
        if (poster != null && !USER_ID.matcher(poster).matches()) {
            return new DiscordLeakCheckDTO.Error("posterDiscordUserId must be a Discord user id", ErrorKind.VALIDATION);
        }

        Set<String> punishmentIds = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : request.candidates()) {
            if (seen.size() >= this.properties.maxLeakCandidates()) {
                break;
            }
            Optional<String> code = candidate == null ? Optional.empty() : BanIdCodec.normalize(candidate);
            if (code.isPresent() && seen.add(code.get())) {
                this.banIds.findIdentifier(code.get()).ifPresent(punishmentIds::add);
            }
        }

        int marked = 0;
        for (String id : punishmentIds) {
            PunishmentEntity punishment = this.punishments.findById(id).orElse(null);
            if (punishment != null && mark(punishment, poster, request.posterIsBot(), bot)) {
                marked++;
            }
        }
        return new DiscordLeakCheckDTO.Result(punishmentIds.size(), marked);
    }

    private boolean mark(PunishmentEntity punishment, String posterDiscordUserId, boolean posterIsBot, Actor bot) {
        String id = punishment.getIdentifier();
        long now = this.clock.millis();
        CaseChanges.Poster poster = null;
        if (posterDiscordUserId != null && !posterIsBot) {
            Participant participant = this.tickets.participantByDiscordUser(id, posterDiscordUserId)
                    .orElseGet(() -> this.tickets.ensureParticipant(id, ParticipantKind.EXTERNAL, posterDiscordUserId, null, false, now));
            poster = new CaseChanges.Poster(participant.id().toString(), participant.kind() == ParticipantKind.PLAYER);
        }
        CaseActor by = new CaseActor(ActorTrust.SYSTEM, null, bot.oid(), null);
        this.changes.ensureCase(id, now);
        CaseChanges.LeakMarked marked = this.changes.markBanIdLeaked(id, BanIdLeakReason.POSTED_PUBLIC, BanIdLeakSource.DISCORD_BOT, now, by, poster);
        if (marked == null) {
            return false;
        }
        Map<String, Object> change = new HashMap<>();
        change.put("reason", BanIdLeakReason.POSTED_PUBLIC.name());
        change.put("source", BanIdLeakSource.DISCORD_BOT.name());
        change.put("afterFiling", marked.afterFiling());
        if (poster != null) {
            change.put("poster", poster.participantId());
            change.put("posterIsOwner", poster.isOwner());
        }
        new CaseEventPublisher(this.events).publish(punishment, marked.event(), by, now, change);
        return true;
    }
}
