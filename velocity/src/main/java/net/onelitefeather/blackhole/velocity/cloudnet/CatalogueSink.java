package net.onelitefeather.blackhole.velocity.cloudnet;

/** Where the {@link CatalogueReporter} sends a snapshot; the backend in production. */
public interface CatalogueSink {

    /**
     * Sends a full snapshot, replacing the proxy's previous one.
     *
     * @throws Exception if the backend could not be reached or refused it
     */
    void report(String proxyId, Catalogue catalogue) throws Exception;
}
