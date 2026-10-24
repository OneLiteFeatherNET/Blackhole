package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Opened;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.GatewayException;
import net.onelitefeather.blackhole.discord.ticket.TicketLifecycle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * {@code /appeal}: the logic behind the button, the slash command and the modal, free of any Discord type. The player types
 * the Ban ID into an ephemeral modal; it is passed to the backend and never kept, logged or echoed. Every reason the backend has
 * for refusing (wrong ID, leaked ID, an ID bound to another Discord user) arrives as the same answer and gets the same text,
 * so the form cannot be used to probe Ban IDs.
 */
public final class AppealCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppealCommands.class);
    public static final String PANEL_MARKER = "blackhole-appeal-panel";

    private final BackendGateway backend;
    private final TicketLifecycle lifecycle;

    public AppealCommands(@NotNull BackendGateway backend, @NotNull TicketLifecycle lifecycle) {
        this.backend = backend;
        this.lifecycle = lifecycle;
    }

    /**
     * @param lang        the language of the reply
     * @param discordUserId the user who submitted the form
     * @param displayName their Discord name (kept only to render the panel)
     * @param banId       what the user typed; never logged
     * @param statement   the statement, possibly blank
     * @return the ephemeral reply
     */
    public @NotNull String open(@NotNull Lang lang, @NotNull String discordUserId, @Nullable String displayName, @Nullable String banId,
                                @Nullable String statement) {
        if (banId == null || banId.isBlank()) {
            return Messages.appealMissingBanId(lang);
        }
        String text = statement == null || statement.isBlank() ? null : statement.strip();
        Result<Opened> result = this.backend.openTicket(discordUserId, displayName, banId.strip(), text);
        if (result instanceof Result.Failed<Opened> failed) {
            return failure(lang, failed);
        }
        Opened opened = ((Result.Ok<Opened>) result).value();
        Long channel = opened.ticket().channel();
        if (opened.ticket().state() == State.PENDING_CHANNEL) {
            Optional<Long> made = this.lifecycle.ensureChannel(opened.ticket());
            channel = made.orElse(null);
        }
        if (channel == null) {
            return Messages.appealPreparing(lang);
        }
        return Messages.appealOpened(lang, channel, opened.created());
    }

    private static String failure(Lang lang, Result.Failed<Opened> failed) {
        if (failed.unavailable()) {
            LOGGER.warn("Opening a ticket: the backend is not available (status {})", failed.status());
            return Messages.unavailable(lang);
        }
        if (failed.is("STATEMENT_REQUIRED")) {
            return Messages.appealStatementRequired(lang);
        }
        if (failed.is("NOT_YET") && failed.availableFrom() != null) {
            return Messages.appealNotYet(lang, failed.availableFrom());
        }
        return Messages.appealNotEligible(lang);
    }

    // ---- texts the JDA adapter needs for the modal and the panel ----

    public @NotNull String modalTitle(@NotNull Lang lang) {
        return Messages.appealModalTitle(lang);
    }

    public @NotNull String banIdLabel(@NotNull Lang lang) {
        return Messages.appealBanIdLabel(lang);
    }

    public @NotNull String statementLabel(@NotNull Lang lang) {
        return Messages.appealStatementLabel(lang);
    }

    public @NotNull String generic(@NotNull Lang lang) {
        return Messages.unavailable(lang);
    }

    /** Makes sure the "open an appeal" button message exists in the appeal channel. A failure is logged; the slash command still works. */
    public void ensurePanel(@NotNull DiscordGateway discord, long channelId) {
        try {
            discord.ensureAppealPanel(channelId, Messages.appealPanelTitle(), Messages.appealPanelBody(), Messages.appealPanelButton(), PANEL_MARKER);
        } catch (GatewayException e) {
            LOGGER.warn("Could not post the appeal button message (permanent={}); /appeal still works", e.permanent());
        }
    }
}
