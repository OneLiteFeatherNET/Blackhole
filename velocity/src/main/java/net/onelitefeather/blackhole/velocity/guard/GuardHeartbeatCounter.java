package net.onelitefeather.blackhole.velocity.guard;

import com.velocitypowered.api.permission.PermissionSubject;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/** Counts the online players that hold the staff permission node, for the presence heartbeat. */
public final class GuardHeartbeatCounter {

    private GuardHeartbeatCounter() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param online the players online on this proxy right now
     * @return how many of them hold {@value Permissions#STAFF}
     */
    public static int staffOnline(@NotNull Collection<? extends PermissionSubject> online) {
        return (int) online.stream().filter(player -> player.hasPermission(Permissions.STAFF)).count();
    }
}
