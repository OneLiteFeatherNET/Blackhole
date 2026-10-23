package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import net.onelitefeather.blackhole.velocity.report.ReportFeedbackDelivery;
import org.jetbrains.annotations.NotNull;

/**
 * Tells a reporter who was offline when their report was resolved what became of it, as soon as they joined.
 */
public final class ReportFeedbackListener {

    private final ReportFeedbackDelivery delivery;

    @Inject
    public ReportFeedbackListener(@NotNull ReportFeedbackDelivery delivery) {
        this.delivery = delivery;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        this.delivery.deliverLater(event.getPlayer());
    }
}
