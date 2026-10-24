package net.onelitefeather.blackhole.discord.ticket;

import net.onelitefeather.blackhole.discord.config.BotConfig.TicketSettings;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway.Permission;
import net.onelitefeather.blackhole.discord.metrics.Health;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks at start (and again now and then) that the bot has what it needs in the ticket and archive categories, and that the
 * staff role has no View there. A missing permission is named by category and permission and reported on the health endpoint,
 * so a wrong setup is loud instead of failing quietly on the first ticket.
 */
public final class PermissionSelfCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionSelfCheck.class);

    private final DiscordGateway discord;
    private final TicketSettings settings;
    private final long staffRoleId;
    private final Health health;

    public PermissionSelfCheck(@NotNull DiscordGateway discord, @NotNull TicketSettings settings, long staffRoleId, @NotNull Health health) {
        this.discord = discord;
        this.settings = settings;
        this.staffRoleId = staffRoleId;
        this.health = health;
    }

    /** @return the problems found; also published on {@link Health} */
    public @NotNull List<String> run() {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        check("ticket category", this.settings.ticketCategoryId(), problems, warnings);
        check("archive category", this.settings.archiveCategoryId(), problems, warnings);
        if (this.settings.appealChannelId() != 0) {
            Set<Permission> missing = this.discord.missingPermissions(this.settings.appealChannelId());
            for (Permission permission : missing) {
                if (permission == Permission.VIEW || permission == Permission.SEND || permission == Permission.EMBED_LINKS) {
                    problems.add("appeal channel: missing " + permission);
                }
            }
        }
        problems.forEach(problem -> LOGGER.error("Permission self-check: {}", problem));
        warnings.forEach(warning -> LOGGER.warn("Permission self-check: {}", warning));
        this.health.problems(problems);
        this.health.warnings(warnings);
        return problems;
    }

    private void check(String name, long category, List<String> problems, List<String> warnings) {
        for (Permission permission : this.discord.missingPermissions(category)) {
            problems.add(name + ": missing " + permission);
        }
        if (this.discord.roleCanView(category, this.staffRoleId)) {
            warnings.add(name + ": the staff role can view it; staff must see ticket channels only through a linked account");
        }
    }
}
