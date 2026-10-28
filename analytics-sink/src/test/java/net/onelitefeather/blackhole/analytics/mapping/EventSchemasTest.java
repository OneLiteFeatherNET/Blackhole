package net.onelitefeather.blackhole.analytics.mapping;

import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSchemasTest {

    @Test
    void everyFamilySchemaHasTheCommonColumnsAndExtra() {
        for (EventFamily family : EventFamily.values()) {
            Schema schema = EventSchemas.schema(family);

            for (String column : new String[] {"event_id", "event_type", "occurred_at", "payload_version", "owner", "extra"}) {
                assertNotNull(schema.findField(column), family + " schema must have the column " + column);
            }
        }
    }

    @Test
    void punishmentSchemaTypesTheTypedColumnsAndTheirValues() {
        Schema schema = EventSchemas.schema(EventFamily.PUNISHMENT);

        assertEquals(Types.BooleanType.get(), schema.findField("permanent").type(), "permanent is a boolean column");
        assertEquals(Types.StringType.get(), schema.findField("punishment_identifier").type(), "identifiers are strings");
        assertTrue(schema.findField("expires_at") != null, "expires_at is a typed column");
    }

    @Test
    void fieldIdsAreUniqueWithinAFamilySchema() {
        for (EventFamily family : EventFamily.values()) {
            Schema schema = EventSchemas.schema(family);

            assertEquals(schema.columns().size(), schema.idToName().size(), family + " schema must not repeat a field id");
        }
    }
}
