package net.onelitefeather.blackhole.velocity.report;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.ReportMessages;
import net.onelitefeather.blackhole.velocity.nats.NatsSyncService;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Tells the online staff of this proxy about a new report. The backend announces every report on the
 * event bus ({@code report.created}, hashes only); each proxy then reads the report over HTTP and takes
 * the players' names from the players it has online, falling back to the names the report was filed with.
 * A proxy only ever tells its own staff, so each staff member hears of a report exactly once.
 *
 * <p>While this proxy is not connected to the bus it would never hear of the report, so the proxy that
 * took the report {@linkplain #announce tells its own staff itself}.</p>
 */
public final class ReportNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportNotifier.class);
    private static final String UNKNOWN_PLAYER = "?";

    private final Supplier<Collection<? extends Player>> players;
    private final Supplier<String> panelUrl;
    private final BackendGateway backend;
    private final BooleanSupplier busConnected;
    private final Executor async;

    /**
     * @param players      the players connected to this proxy
     * @param panelUrl     the admin panel's address, null to show no panel button
     * @param busConnected whether this proxy receives the backend's events right now
     * @param async        runs the report lookup off the event thread
     */
    public ReportNotifier(
            @NotNull Supplier<Collection<? extends Player>> players, @NotNull Supplier<String> panelUrl,
            @NotNull BackendGateway backend, @NotNull BooleanSupplier busConnected, @NotNull Executor async
    ) {
        this.players = players;
        this.panelUrl = panelUrl;
        this.backend = backend;
        this.busConnected = busConnected;
        this.async = async;
    }

    /**
     * Called by the proxy that took a report: when the bus is down this proxy will not hear of it, so it
     * tells its own staff directly. When the bus is up the event does the job on every proxy.
     */
    public void announce(@NotNull ReportNotice notice) {
        if (!this.busConnected.getAsBoolean()) {
            deliver(notice);
        }
    }

    /** A {@code report.created} event arrived: look the report up and tell this proxy's staff. */
    public void reportCreated(@NotNull NatsSyncService.ReportCreated event) {
        this.async.execute(() -> {
            try {
                if (!hasStaffOnline()) {
                    return;
                }
                deliver(noticeOf(event));
            } catch (RuntimeException e) {
                LOGGER.error("A report notice could not be delivered: {}", e.getMessage());
            }
        });
    }

    private boolean hasStaffOnline() {
        for (Player player : this.players.get()) {
            if (player.hasPermission(Permissions.REPORT_NOTIFY)) {
                return true;
            }
        }
        return false;
    }

    private ReportNotice noticeOf(NatsSyncService.ReportCreated event) {
        ReportDTO report = lookup(event.reportIdentifier());
        String reporter = nameOf(event.reporterHash() != null ? event.reporterHash() : report == null ? null : report.getReporterHash(),
                report, "reporterName");
        String reported = nameOf(event.reportedHash() != null ? event.reportedHash() : report == null ? null : report.getReportedHash(),
                report, "reportedName");
        String category = event.category() != null ? event.category()
                : report != null && report.getCategory() != null ? report.getCategory().getValue() : "OTHER";
        return new ReportNotice(event.reportIdentifier(), reporter, reported, category,
                ReportNotice.excerptOf(report == null ? null : report.getDescription()));
    }

    private @Nullable ReportDTO lookup(String identifier) {
        try {
            return this.backend.report(UUID.fromString(identifier));
        } catch (Exception e) {
            LOGGER.warn("Could not read report {} for the staff notice, using what the event carries: {}", identifier, e.getMessage());
            return null;
        }
    }

    /** The name of the online player with this owner hash, else the name the report was filed with, else a placeholder. */
    private String nameOf(@Nullable String hash, @Nullable ReportDTO report, String metaKey) {
        if (hash != null) {
            for (Player player : this.players.get()) {
                if (UUIDConverter.convertToSHA(player.getUniqueId()).equals(hash)) {
                    return player.getUsername();
                }
            }
        }
        if (report != null && report.getMetaData() != null && report.getMetaData().get(metaKey) instanceof String name && !name.isBlank()) {
            return name;
        }
        return UNKNOWN_PLAYER;
    }

    /** Tells this proxy's online staff who may be notified. */
    public void deliver(@NotNull ReportNotice notice) {
        String panel = this.panelUrl.get();
        for (Player player : this.players.get()) {
            if (player.hasPermission(Permissions.REPORT_NOTIFY)) {
                player.sendMessage(ReportMessages.notice(notice, panel));
            }
        }
    }
}
