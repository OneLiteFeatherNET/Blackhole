package net.onelitefeather.blackhole.backend.punishment;

import io.micronaut.context.ApplicationContext;
import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class PunishmentTemplatePersistenceTest extends PostgresIntegrationTest {

    @Test
    void templateWithStepsAndJsonMetadataRoundTripsThroughPostgres() {
        try (ApplicationContext context = ApplicationContext.run(contextProperties())) {
            PunishmentTemplateRepository repository = context.getBean(PunishmentTemplateRepository.class);

            Map<String, Object> metaData = new HashMap<>();
            metaData.put("translatable", true);
            metaData.put("nested", Map.of("key", "value"));
            PunishmentTemplateEntity saved = repository.save(new PunishmentTemplateEntity(
                    null, "persistence-spam", "Spam", "chat", "persistence-spam", "P30D",
                    List.of(new TemplateStep("PT1H", 10), new TemplateStep("PT6H", 20), new TemplateStep("P1D", 30)), metaData));

            PunishmentTemplateEntity found = repository.findById(saved.getIdentifier()).orElseThrow();
            assertEquals(metaData, found.getMetaData(), "JSON metadata must survive a save/load round trip through PostgreSQL jsonb");
            assertEquals(List.of("PT1H", "PT6H", "P1D"), found.getSteps().stream().map(TemplateStep::getDuration).toList(),
                    "steps keep their order");
            assertEquals(List.of(10, 20, 30), found.getSteps().stream().map(TemplateStep::getEloCost).toList());
            assertEquals("P30D", found.getEscalationWindow());
            assertEquals("persistence-spam", repository.findByTemplateKey("persistence-spam").orElseThrow().getTemplateKey());
        }
    }
}
