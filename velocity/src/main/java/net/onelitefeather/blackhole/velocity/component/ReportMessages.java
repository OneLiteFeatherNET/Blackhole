package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.velocity.report.ReportCategories;
import net.onelitefeather.blackhole.velocity.report.ReportEvidence;
import net.onelitefeather.blackhole.velocity.report.ReportNotice;
import net.onelitefeather.blackhole.velocity.report.ReportService.Outcome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a player is told after {@code /report}. These are plain texts: a reporter is not staff, so there
 * are no hovers or clicks that would expose staff commands.
 */
public final class ReportMessages {

    /** The syntax shown with an unusable report command. */
    public static final String USAGE = "/report <player> <chat|cheating|griefing|other> [details]";

    private ReportMessages() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** @return the answer to a report that ended with this outcome */
    public static @NotNull Component outcome(@NotNull Outcome outcome, @NotNull String reportedName, @NotNull ReportCategory category) {
        return switch (outcome) {
            case Outcome.Submitted submitted -> submitted(reportedName, category, submitted.evidence());
            case Outcome.SelfReport ignored -> Component.translatable("report.error.self");
            case Outcome.Protected ignored -> Component.translatable("report.error.protected", Component.text(reportedName));
            case Outcome.DetailsRequired ignored -> Component.translatable("report.error.details_required");
            case Outcome.DetailsTooLong tooLong -> Component.translatable("report.error.details_too_long", Component.text(tooLong.max()));
            case Outcome.RateLimited ignored -> Component.translatable("report.error.rate_limited");
            case Outcome.Failed ignored -> Component.translatable("report.error.failed");
        };
    }

    /** @return the answer to a reason that is none of the categories, with the ones to pick from */
    public static @NotNull Component unknownCategory(@NotNull String typed) {
        return Component.join(JoinConfiguration.newlines(),
                Component.translatable("report.error.category", Component.text(typed), Component.text(String.join(", ", ReportCategories.words()))),
                usage());
    }

    /** @return the usage line */
    public static @NotNull Component usage() {
        return Component.translatable("report.usage", Component.text(USAGE));
    }

    /** @return the answer to a reporter whose permission was explicitly taken away */
    public static @NotNull Component denied() {
        return Component.translatable("report.error.denied");
    }

    /**
     * What staff see for a new report: who reported whom for what, a short excerpt of the text, and two
     * buttons. "Open" suggests the info command of the reported player (it never runs it); "panel" opens the
     * report page and is only offered when a panel address is configured and the report has an id.
     *
     * @param panelUrl the admin panel's address without a trailing slash, or null
     */
    public static @NotNull Component notice(@NotNull ReportNotice notice, @Nullable String panelUrl) {
        Component category = Component.translatable(categoryKey(notice.category()));
        Component header = Component.translatable("report.notice.header",
                Component.text(notice.reporter()), Component.text(notice.reported()), category);

        String infoCommand = "/blackhole " + notice.reported() + " info";
        Component open = Component.translatable("report.notice.button.open")
                .clickEvent(ClickEvent.suggestCommand(infoCommand))
                .hoverEvent(HoverEvent.showText(Component.translatable("report.notice.hover.open", Component.text(infoCommand))));
        Component buttons = open;
        if (panelUrl != null && notice.reportId() != null) {
            Component panel = Component.translatable("report.notice.button.panel")
                    .clickEvent(ClickEvent.openUrl(panelUrl + "/reports/" + notice.reportId()))
                    .hoverEvent(HoverEvent.showText(Component.translatable("report.notice.hover.panel")));
            buttons = Component.join(JoinConfiguration.separator(Component.space()), open, panel);
        }

        List<Component> lines = new ArrayList<>();
        lines.add(header);
        if (!notice.excerpt().isEmpty()) {
            lines.add(Component.translatable("report.notice.text", Component.text(notice.excerpt())));
        }
        lines.add(Component.translatable("report.notice.buttons", buttons));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static String categoryKey(String category) {
        return ReportCategories.parse(category.toLowerCase(Locale.ROOT).replace("_abuse", ""))
                .map(ReportCategories::nameKey)
                .orElse("report.category.other");
    }

    private static Component submitted(String reportedName, ReportCategory category, ReportEvidence.Result evidence) {
        Component confirmation = Component.translatable("report.success",
                Component.text(reportedName), Component.translatable(ReportCategories.nameKey(category)));
        if (evidence.status() != ReportEvidence.Result.Status.ATTACHED) {
            return confirmation;
        }
        List<Component> lines = List.of(confirmation,
                Component.translatable("report.success.evidence", Component.text(evidence.lines())));
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
