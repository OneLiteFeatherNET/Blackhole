package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.velocity.report.ReportCategories;
import net.onelitefeather.blackhole.velocity.report.ReportEvidence;
import net.onelitefeather.blackhole.velocity.report.ReportService.Outcome;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
