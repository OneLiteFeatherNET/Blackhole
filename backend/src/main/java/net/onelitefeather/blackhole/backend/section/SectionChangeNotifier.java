package net.onelitefeather.blackhole.backend.section;

/**
 * Tells every proxy that the section list changed, so it reloads it. Best effort: a failure to
 * notify must never fail the write that triggered it (proxies also reload when their copy expires).
 */
public interface SectionChangeNotifier {

    /**
     * @param sectionKey the section that was created, updated or disabled
     */
    void changed(String sectionKey);
}
