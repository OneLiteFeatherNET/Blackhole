package net.onelitefeather.blackhole.web;

import net.onelitefeather.blackhole.request.entry.PunishEntryRequests;
import net.onelitefeather.blackhole.request.entry.PunishWebRequests;
import net.onelitefeather.blackhole.request.profile.ProfileWebRequests;
import net.onelitefeather.blackhole.request.profile.PunishProfileRequests;
import net.onelitefeather.blackhole.request.template.PunishTemplateRequests;
import net.onelitefeather.blackhole.request.template.TemplateWebRequests;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpClient;
import java.time.Duration;

public final class BlackholeWebClient implements BlackholeClient {

    private final TemplateWebRequests templateWebRequests;
    private final ProfileWebRequests profileWebRequests;
    private final PunishWebRequests punishProfileRequests;

    public BlackholeWebClient(@NotNull String url, @NotNull Duration timeout) {
        HttpClient httpClient = HttpClient
                .newBuilder()
                .connectTimeout(timeout)
                .build();
        this.templateWebRequests = new PunishTemplateRequests(url, httpClient);
        this.profileWebRequests = new PunishProfileRequests(url, httpClient);
        this.punishProfileRequests = new PunishEntryRequests(url, httpClient);
    }

    @Override
    public @NotNull TemplateWebRequests templateRequests() {
        return this.templateWebRequests;
    }

    @Override
    public @NotNull ProfileWebRequests profileRequests() {
        return this.profileWebRequests;
    }

    @Override
    public @NotNull PunishWebRequests punishRequests() {
        return this.punishProfileRequests;
    }
}
