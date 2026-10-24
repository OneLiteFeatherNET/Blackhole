package net.onelitefeather.blackhole.backend.discord;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appeal.AppealEntity;
import net.onelitefeather.blackhole.backend.appeal.AppealRepository;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordNotificationDTO;
import net.onelitefeather.blackhole.backend.report.ReportEntity;
import net.onelitefeather.blackhole.backend.report.ReportRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.util.Map;
import java.util.UUID;

/**
 * Read model of the team notifications: the display facts of a report or an appeal for the Discord bot.
 * The events that trigger a notification carry only hashed owners, and the backend stores no player
 * names, so a name is only present when the proxy recorded one in the metadata of the report
 * ({@code reportedName}) or of the appeal or its punishment ({@code appellantName} / {@code targetName}).
 * Otherwise the bot shows {@code playerRef}, the first 8 characters of the hash. Text is shortened so a
 * long statement never fills the channel.
 */
@Singleton
public class DiscordNotificationService {

    static final int TEXT_LIMIT = 300;
    static final int REF_LENGTH = 8;

    private final ReportRepository reports;
    private final AppealRepository appeals;

    public DiscordNotificationService(ReportRepository reports, AppealRepository appeals) {
        this.reports = reports;
        this.appeals = appeals;
    }

    public DiscordNotificationDTO report(UUID identifier) {
        return this.reports.findById(identifier)
                .<DiscordNotificationDTO>map(DiscordNotificationService::toDto)
                .orElseGet(() -> new DiscordNotificationDTO.Error("there is no report " + identifier, ErrorKind.NOT_FOUND));
    }

    public DiscordNotificationDTO appeal(UUID identifier) {
        return this.appeals.findById(identifier)
                .<DiscordNotificationDTO>map(DiscordNotificationService::toDto)
                .orElseGet(() -> new DiscordNotificationDTO.Error("there is no appeal " + identifier, ErrorKind.NOT_FOUND));
    }

    private static DiscordNotificationDTO.Report toDto(ReportEntity report) {
        return new DiscordNotificationDTO.Report(
                report.getIdentifier(),
                name(report.getMetaData(), "reportedName"),
                ref(report.getReportedHash()),
                report.getCategory().name(),
                shorten(report.getDescription()),
                report.getStatus().name(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

    private static DiscordNotificationDTO.Appeal toDto(AppealEntity appeal) {
        String name = name(appeal.getMetaData(), "appellantName");
        if (name == null) {
            name = name(appeal.getPunishment().getMetaData(), "targetName");
        }
        return new DiscordNotificationDTO.Appeal(
                appeal.getIdentifier(),
                appeal.getPunishment().getIdentifier(),
                name,
                ref(appeal.getAppellantHash()),
                appeal.getPunishment().getSectionKey(),
                shorten(appeal.getStatement()),
                appeal.getStatus().name(),
                appeal.getCreatedAt(),
                appeal.getUpdatedAt());
    }

    static String ref(String hash) {
        return hash == null ? "unknown" : hash.substring(0, Math.min(REF_LENGTH, hash.length()));
    }

    static String shorten(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= TEXT_LIMIT ? stripped : stripped.substring(0, TEXT_LIMIT - 1) + "…";
    }

    private static String name(Map<String, Object> metaData, String key) {
        if (metaData != null && metaData.get(key) instanceof String name && !name.isBlank()
                && name.strip().matches("[A-Za-z0-9_]{3,16}")) {
            return name.strip();
        }
        return null;
    }
}
