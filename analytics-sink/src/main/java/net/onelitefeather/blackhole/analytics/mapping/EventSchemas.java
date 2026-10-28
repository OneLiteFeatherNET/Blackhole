package net.onelitefeather.blackhole.analytics.mapping;

import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The Iceberg schema of each family table: common columns, one column per typed key, then {@code extra}. */
public final class EventSchemas {

    private static final Set<String> BOOLEAN_KEYS = Set.of("permanent", "automatic", "verified", "eligible");
    private static final Set<String> LONG_KEYS = Set.of("resultingScore");

    public static Schema schema(EventFamily family) {
        List<Types.NestedField> fields = new ArrayList<>();
        int id = 1;
        fields.add(Types.NestedField.required(id++, "event_id", Types.StringType.get()));
        fields.add(Types.NestedField.required(id++, "event_type", Types.StringType.get()));
        fields.add(Types.NestedField.required(id++, "occurred_at", Types.TimestampType.withZone()));
        fields.add(Types.NestedField.required(id++, "payload_version", Types.IntegerType.get()));
        fields.add(Types.NestedField.optional(id++, "owner", Types.StringType.get()));
        for (String key : family.typedKeys()) {
            fields.add(Types.NestedField.optional(id++, columnName(key), typeOf(key)));
        }
        fields.add(Types.NestedField.optional(id, "extra", Types.StringType.get()));
        return new Schema(fields);
    }

    /** Payload key to column name: {@code punishmentIdentifier} becomes {@code punishment_identifier}. */
    static String columnName(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static boolean isBooleanKey(String key) {
        return BOOLEAN_KEYS.contains(key);
    }

    static boolean isLongKey(String key) {
        return LONG_KEYS.contains(key);
    }

    private static Type typeOf(String key) {
        if (isBooleanKey(key)) {
            return Types.BooleanType.get();
        }
        if (isLongKey(key)) {
            return Types.LongType.get();
        }
        return Types.StringType.get();
    }

    private EventSchemas() {
    }
}
