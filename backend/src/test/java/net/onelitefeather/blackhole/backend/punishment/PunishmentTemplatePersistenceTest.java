package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class PunishmentTemplatePersistenceTest extends PostgresIntegrationTest {

    @Test
    void templateWithJsonMetadataRoundTripsThroughPostgres() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            PunishmentTemplateRepository repository = context.getBean(PunishmentTemplateRepository.class);

            Map<String, Object> metaData = new HashMap<>();
            metaData.put("duration", "PT1H");
            metaData.put("nested", Map.of("key", "value"));
            PunishmentTemplateEntity saved = repository.save(
                    new PunishmentTemplateEntity(null, "spam", PunishType.CHAT, 0, metaData));

            PunishmentTemplateEntity found = repository.findById(saved.getIdentifier()).orElseThrow();
            assertEquals(metaData, found.getMetaData(), "JSON metadata must survive a save/load round trip through PostgreSQL jsonb");
        }
    }
}
