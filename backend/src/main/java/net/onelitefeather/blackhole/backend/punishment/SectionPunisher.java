package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The punishments the ELO system applies on its own, without a template and without an ELO cost.
 * Implemented by the punishment application service; the ELO engine depends on this narrow
 * interface only.
 */
public interface SectionPunisher {

    /**
     * Whether the owner currently has an active permanent punishment in the section.
     */
    boolean hasActivePermanent(String owner, String sectionKey);

    /**
     * Applies a permanent punishment carrying the section's permanent reason. It replaces the
     * section's active punishment, which moves to history.
     *
     * @param extraMetaData context recorded on the punishment (for example the triggering reason code)
     * @return the identifier of the created punishment, or empty if it could not be applied
     */
    Optional<String> applyPermanent(String owner, SectionDTO.Response section, Map<String, Object> extraMetaData);

    /**
     * Applies a temporary punishment of the given duration.
     *
     * @return the identifier of the created punishment, or empty if it could not be applied
     */
    Optional<String> applySoft(String owner, SectionDTO.Response section, Duration duration, Map<String, Object> extraMetaData);
}
