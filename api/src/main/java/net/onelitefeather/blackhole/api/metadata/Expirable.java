package net.onelitefeather.blackhole.api.metadata;

public interface Expirable extends Metadata {

    String META_DATA_KEY_EXPIRATION_DATE = "expiration_date";

    /**
     * The date when the object will expire.
     *
     * @return the expiration date
     */
    long expirationDate();
}
