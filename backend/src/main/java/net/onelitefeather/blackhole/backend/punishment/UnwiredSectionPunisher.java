package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Transitional {@link SectionPunisher} until the punishment service applies section punishments.
 */
@Secondary
@Singleton
public class UnwiredSectionPunisher implements SectionPunisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnwiredSectionPunisher.class);

    @Override
    public boolean hasActivePermanent(String owner, String sectionKey) {
        return false;
    }

    @Override
    public Optional<String> applyPermanent(String owner, SectionDTO.Response section, Map<String, Object> extraMetaData) {
        LOGGER.warn("Permanent escalation in {} is not wired yet", section.key());
        return Optional.empty();
    }

    @Override
    public Optional<String> applySoft(String owner, SectionDTO.Response section, Duration duration, Map<String, Object> extraMetaData) {
        LOGGER.warn("Soft punishment in {} is not wired yet", section.key());
        return Optional.empty();
    }
}
