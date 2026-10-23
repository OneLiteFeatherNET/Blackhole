package net.onelitefeather.blackhole.velocity.component;

import com.velocitypowered.api.permission.PermissionSubject;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * What the receiver of a message may do, so a message offers only buttons the receiver could use. A view
 * over a predicate keeps the message factories free of the proxy.
 */
public final class Permissions {

    public static final String UNBAN = "blackhole.unban";
    public static final String UNMUTE = "blackhole.unmute";
    public static final String PARDON_PERMANENT = "blackhole.pardon.permanent";
    public static final String LINK = "blackhole.link";
    /** Granted to everybody unless a permission plugin explicitly denies it. */
    public static final String REPORT = "blackhole.report";
    /** Staff holding it cannot be reported. */
    public static final String REPORT_BYPASS = "blackhole.report.bypass";
    /** Staff holding it are told about new reports in game. */
    public static final String REPORT_NOTIFY = "blackhole.report.notify";

    private final Predicate<String> granted;

    public Permissions(@NotNull Predicate<String> granted) {
        this.granted = granted;
    }

    public static @NotNull Permissions of(@NotNull PermissionSubject subject) {
        return new Permissions(subject::hasPermission);
    }

    /** @return whether the receiver may lift permanent punishments */
    public boolean mayPardonPermanent() {
        return this.granted.test(PARDON_PERMANENT);
    }

    /**
     * @return whether the receiver may lift a punishment of this kind; a permanent one needs
     * {@value #PARDON_PERMANENT} on top
     */
    public boolean mayUndo(@NotNull EnforcementKind kind, boolean permanent) {
        String needed = kind == EnforcementKind.MUTE ? UNMUTE : UNBAN;
        return this.granted.test(needed) && (!permanent || this.granted.test(PARDON_PERMANENT));
    }
}
