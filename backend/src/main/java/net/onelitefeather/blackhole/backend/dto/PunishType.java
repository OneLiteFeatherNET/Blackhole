package net.onelitefeather.blackhole.backend.dto;

/**
 * This enumeration defines each type of ban which is supported by the application.
 * <p>
 * The following types are supported:
 * <ul>
 *     <li>Server: means that a target is banned from a specific server</li>
 *     <li>Network: means that a target is banned from the whole network</li>
 *     <li>Chat: means that a target is banned from the chat</li>
 * </ul>
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public enum PunishType {

    SERVER,
    NETWORK,
    CHAT
}
