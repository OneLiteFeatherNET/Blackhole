package net.onelitefeather.blackhole.discord.testing;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.Result;
import net.onelitefeather.blackhole.discord.backend.Tickets.Closed;
import net.onelitefeather.blackhole.discord.backend.Tickets.IngestBatch;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ingested;
import net.onelitefeather.blackhole.discord.backend.Tickets.Opened;
import net.onelitefeather.blackhole.discord.backend.Tickets.Outbox;
import net.onelitefeather.blackhole.discord.backend.Tickets.Patch;
import net.onelitefeather.blackhole.discord.backend.Tickets.ProtocolEnsured;
import net.onelitefeather.blackhole.discord.backend.Tickets.State;
import net.onelitefeather.blackhole.discord.backend.Tickets.Ticket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/** A backend for tests that only use part of the interface: every call it does not override fails loudly. */
public abstract class UnsupportedBackend implements BackendGateway {

    @Override
    public @NotNull Result<Opened> openTicket(@NotNull String discordUserId, @Nullable String displayName, @NotNull String banId, @Nullable String statement) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<List<Ticket>> tickets(@NotNull Collection<State> states) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<Ticket> ticket(@NotNull String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<Ticket> patchTicket(@NotNull String id, @NotNull Patch patch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<Outbox> outbox(@NotNull String id, long afterSeq, int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<Long> linkRelay(@NotNull String id, long seq, @Nullable String messageId, @Nullable String discordMessageId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<Ingested> ingest(@NotNull String id, @NotNull IngestBatch batch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<Closed> close(@NotNull String id, int count, @Nullable String lastMessageId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<ProtocolEnsured> ensureProtocol(@NotNull String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Result<List<String>> staffLinks() {
        throw new UnsupportedOperationException();
    }
}
