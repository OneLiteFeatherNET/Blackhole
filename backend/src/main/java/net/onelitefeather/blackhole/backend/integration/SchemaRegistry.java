package net.onelitefeather.blackhole.backend.integration;

import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JSON Schemas (2020-12) of the integration payloads, keyed by (source system, kind, schema version). The files are
 * under {@code integration-schemas/<sourceSystem>/<kind>/v<n>.json} and are loaded once, when the registry is created;
 * the map is immutable afterwards. A schema is a code change: it is listed in {@link #BUNDLED} and reviewed like any other.
 */
@Singleton
public final class SchemaRegistry {

    static final String RESOURCE_ROOT = "integration-schemas/";

    /** Every bundled schema file, relative to {@link #RESOURCE_ROOT}. A file that is not listed here is not loaded. */
    static final List<String> BUNDLED = List.of("vulcan/DETECTION/v1.json");

    private static final Pattern SCHEMA_PATH = Pattern.compile("^([a-z][a-z0-9-]*)/([A-Z][A-Z0-9_]*)/v([1-9][0-9]{0,2})\\.json$");

    /** The triple a schema is registered under. */
    public record Key(String sourceSystem, String kind, int version) {
    }

    private final Map<Key, Schema> schemas;

    @Inject
    public SchemaRegistry() {
        this(loadBundled());
    }

    private SchemaRegistry(Map<Key, Schema> schemas) {
        this.schemas = Map.copyOf(schemas);
    }

    /**
     * A registry of the given schema texts.
     *
     * @param schemaByPath the text of each schema, keyed by its path relative to the schema root,
     *                     for example {@code vulcan/DETECTION/v1.json}
     * @throws IllegalArgumentException when a path does not name a schema, or a schema does not compile
     */
    public static SchemaRegistry of(Map<String, String> schemaByPath) {
        Map<Key, Schema> compiled = new LinkedHashMap<>();
        com.networknt.schema.SchemaRegistry factory = factory();
        schemaByPath.forEach((path, text) -> compiled.put(keyOf(path), factory.getSchema(text)));
        return new SchemaRegistry(compiled);
    }

    /** The compiled schema for the triple, or empty when none is registered for it. */
    public Optional<Schema> find(String sourceSystem, String kind, int version) {
        return Optional.ofNullable(this.schemas.get(new Key(sourceSystem, kind, version)));
    }

    /** Every registered triple. */
    public List<Key> keys() {
        return List.copyOf(this.schemas.keySet());
    }

    private static Map<Key, Schema> loadBundled() {
        Map<String, String> texts = new LinkedHashMap<>();
        for (String path : BUNDLED) {
            texts.put(path, read(RESOURCE_ROOT + path));
        }
        return of(texts).schemas;
    }

    private static String read(String resource) {
        try (InputStream in = SchemaRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("integration schema is missing from the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("integration schema cannot be read: " + resource, e);
        }
    }

    private static Key keyOf(String path) {
        Matcher matcher = SCHEMA_PATH.matcher(path);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a schema path (<sourceSystem>/<KIND>/v<n>.json): " + path);
        }
        return new Key(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
    }

    /** The dialect of every schema: draft 2020-12, the default when a schema names none. */
    private static com.networknt.schema.SchemaRegistry factory() {
        return com.networknt.schema.SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    }
}
