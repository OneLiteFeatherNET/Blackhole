package net.onelitefeather.blackhole.backend.staffprofile;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.security.StaffLinkEntity;
import net.onelitefeather.blackhole.backend.security.StaffLinkRepository;

/** The weekly refresh of every linked staff head. A failed fetch keeps the cached head (see {@link StaffAvatarJob}). */
@Singleton
public class StaffAvatarSweeper {

    private final StaffLinkRepository links;
    private final StaffAvatarJob avatars;

    public StaffAvatarSweeper(StaffLinkRepository links, StaffAvatarJob avatars) {
        this.links = links;
        this.avatars = avatars;
    }

    @Scheduled(cron = "${blackhole.staff.avatar.refresh-cron:0 30 3 * * MON}")
    public void refreshAll() {
        for (StaffLinkEntity link : this.links.findAll()) {
            this.avatars.refresh(link.getOid(), link.getMinecraftUuid());
        }
    }
}
