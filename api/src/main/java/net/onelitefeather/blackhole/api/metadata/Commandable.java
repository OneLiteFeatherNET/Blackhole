package net.onelitefeather.blackhole.api.metadata;

public interface Commandable extends Metadata {

    String META_DATA_KEY_COMMAND = "command";

    /**
     * The command name of the object.
     *
     * @return the command name
     */
    String command();

    /**
     * Check if the object is commandable.
     *
     * @return {@code true} if the object is commandable
     */
    default boolean commandable() {
        return hasMetaData(META_DATA_KEY_COMMAND);
    }
}
