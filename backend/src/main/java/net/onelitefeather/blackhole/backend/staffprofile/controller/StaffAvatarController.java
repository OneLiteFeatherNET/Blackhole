package net.onelitefeather.blackhole.backend.staffprofile.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.PlayerSessionRule;
import net.onelitefeather.blackhole.backend.staffprofile.StaffAvatarJob;

/**
 * Routing and OpenAPI annotations live on {@link StaffAvatarApi}, which this class implements. The route is under
 * {@code /public}, so only a player session reaches it; the response carries no cookie.
 */
@Version(ApiVersion.V1)
@Controller("/public")
public class StaffAvatarController implements StaffAvatarApi {

    private final StaffAvatarJob avatars;

    @Inject
    public StaffAvatarController(StaffAvatarJob avatars) {
        this.avatars = avatars;
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> avatar(String avatarKey) {
        return this.avatars.png(avatarKey)
                .<HttpResponse<?>>map(png -> HttpResponse.ok(png)
                        .contentType(MediaType.IMAGE_PNG_TYPE)
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400"))
                .orElseGet(HttpResponse::notFound);
    }
}
