package net.onelitefeather.blackhole.velocity.cloudnet;

import org.slf4j.Logger;

import java.util.Locale;

/** Chooses the {@link CloudNetView} at startup: the driver-backed one only if CloudNet is there. */
public final class CloudNets {

    /** {@code config.json} value that disables the integration. */
    public static final String OFF = "off";

    private static final String PROBE_CLASS = "eu.cloudnetservice.driver.inject.InjectionLayer";
    private static final String VIEW_CLASS = "net.onelitefeather.blackhole.velocity.cloudnet.DriverCloudNetView";

    private CloudNets() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Probes for CloudNet and logs one line with the outcome. Never loads a CloudNet class unless the
     * driver is on the classpath, and never fails the plugin.
     *
     * @param integration {@code auto} (default) or {@code off}
     */
    public static CloudNetView detect(String integration, ClassLoader loader, Logger logger) {
        if (integration != null && OFF.equals(integration.strip().toLowerCase(Locale.ROOT))) {
            logger.info("CloudNet integration is disabled (cloudnet.integration = off)");
            return new NoCloudNetView();
        }
        try {
            Class.forName(PROBE_CLASS, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            logger.info("CloudNet not detected, CloudNet sections will not be enforced");
            return new NoCloudNetView();
        }
        try {
            CloudNetView view = (CloudNetView) Class.forName(VIEW_CLASS, true, loader).getDeclaredConstructor().newInstance();
            logger.info("CloudNet detected, CloudNet sections are enforced");
            return view;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            logger.info("CloudNet not detected, CloudNet sections will not be enforced: {}", String.valueOf(e.getCause() != null ? e.getCause() : e));
            return new NoCloudNetView();
        }
    }
}
