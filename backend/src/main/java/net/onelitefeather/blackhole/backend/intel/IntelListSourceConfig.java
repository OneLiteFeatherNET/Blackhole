package net.onelitefeather.blackhole.backend.intel;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * One list source of {@code blackhole.intel.lists.sources.<key>}. The URL is configuration only: the panel cannot
 * point the backend at another address. A disabled source is never downloaded and never classifies anything.
 */
@EachProperty("blackhole.intel.lists.sources")
public class IntelListSourceConfig {

    private final String key;
    private IntelClass kind = IntelClass.VPN;
    private String url = "";
    private boolean enabled;
    private String attribution = "";
    private String licence = "";

    public IntelListSourceConfig(@Parameter String key) {
        this.key = key;
    }

    public String getKey() {
        return this.key;
    }

    public IntelClass getKind() {
        return this.kind;
    }

    public void setKind(IntelClass kind) {
        this.kind = kind;
    }

    public String getUrl() {
        return this.url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAttribution() {
        return this.attribution;
    }

    public void setAttribution(String attribution) {
        this.attribution = attribution;
    }

    public String getLicence() {
        return this.licence;
    }

    public void setLicence(String licence) {
        this.licence = licence;
    }
}
