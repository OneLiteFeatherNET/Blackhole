package net.onelitefeather.blackhole.backend.punishment;

/**
 * What a punishment row is: a real punishment, or a kick or a warning that never stays active. A kick ends the
 * moment it is delivered; a warning waits until the player acknowledges it.
 */
public enum PunishmentKind {
    PUNISHMENT,
    KICK,
    WARN
}
