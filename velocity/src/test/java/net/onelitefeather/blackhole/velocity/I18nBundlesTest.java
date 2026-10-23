package net.onelitefeather.blackhole.velocity;

import net.onelitefeather.blackhole.velocity.component.theme.BlackholeTheme;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin ships English and German texts. Both must define the same keys, with a text each, and
 * every key the code asks for must exist.
 */
class I18nBundlesTest {

    /** Keys the code builds from parts ({@code "punishment.success." + action}), which the scan cannot see. */
    private static final Set<String> COMPOSED_KEYS = Set.of(
            "punishment.success.unban", "punishment.success.unmute",
            "punishment.error.unban", "punishment.error.unmute",
            "punishment.error.unban.none_active", "punishment.error.unmute.none_active");

    /** Keys no code reads. {@code target_not_online} predates the unused-key check and is reworked with the target errors. */
    private static final Set<String> UNREFERENCED_KEYS = Set.of("target_not_online");

    /** A tag of the style; the name after {@code bh:} must be one the theme defines. */
    private static final Pattern THEME_TAG = Pattern.compile("</?bh:([a-zA-Z0-9_]*)>");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([a-z0-9_.]+)\"");

    private static final Pattern LITERAL_KEY = Pattern.compile("translatable\\(\\s*\"([a-z0-9_.]+)\"\\s*[),]");

    private static Properties load(String resource) throws IOException {
        try (InputStream in = I18nBundlesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " must be on the classpath");
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }

    @Test
    void bothLanguagesDefineTheSameKeys() throws IOException {
        Set<String> english = new TreeSet<>(load("blackhole_en.properties").stringPropertyNames());
        Set<String> german = new TreeSet<>(load("blackhole_de.properties").stringPropertyNames());

        assertEquals(english, german, "a key missing in one language shows up as a raw key to its players");
    }

    @Test
    void everyKeyHasAText() throws IOException {
        for (String resource : new String[]{"blackhole_en.properties", "blackhole_de.properties"}) {
            Properties properties = load(resource);
            for (String key : properties.stringPropertyNames()) {
                assertFalse(properties.getProperty(key).isBlank(), resource + " has an empty text for " + key);
            }
        }
    }

    @Test
    void everyKeyTheCodeUsesIsDefined() throws IOException {
        Set<String> defined = load("blackhole_en.properties").stringPropertyNames();
        Set<String> used = new TreeSet<>(COMPOSED_KEYS);
        Path sources = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = LITERAL_KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(defined);

        assertTrue(missing.isEmpty(), "keys used in code but missing from the bundles: " + missing);
        assertTrue(used.size() > COMPOSED_KEYS.size(), "the scan must find the literal keys of the code");
    }

    @Test
    void theTextsWithArgumentsUseTheSamePlaceholdersInBothLanguages() throws IOException {
        Properties english = load("blackhole_en.properties");
        Properties german = load("blackhole_de.properties");
        Pattern placeholder = Pattern.compile("<arg:\\d+>");

        for (String key : english.stringPropertyNames()) {
            Set<String> inEnglish = new TreeSet<>(placeholder.matcher(english.getProperty(key)).results().map(r -> r.group()).toList());
            Set<String> inGerman = new TreeSet<>(placeholder.matcher(german.getProperty(key, "")).results().map(r -> r.group()).toList());
            assertEquals(inEnglish, inGerman, "placeholders of " + key + " differ between the languages");
        }
    }

    /** The names after {@code bh:} that a text uses but the theme does not define. */
    private static Set<String> unknownThemeTags(String text) {
        Set<String> unknown = new TreeSet<>();
        Matcher matcher = THEME_TAG.matcher(text);
        while (matcher.find()) {
            if (!BlackholeTheme.tagNames().contains(matcher.group(1))) {
                unknown.add(matcher.group(1));
            }
        }
        return unknown;
    }

    @Test
    void everyThemeTagInTheTextsIsKnown() throws IOException {
        for (String resource : new String[]{"blackhole_en.properties", "blackhole_de.properties"}) {
            Properties properties = load(resource);
            for (String key : properties.stringPropertyNames()) {
                assertTrue(unknownThemeTags(properties.getProperty(key)).isEmpty(),
                        resource + " uses an unknown bh tag in " + key + ": " + unknownThemeTags(properties.getProperty(key)));
            }
        }
    }

    @Test
    void anUnknownThemeTagIsDetected() {
        assertEquals(Set.of("bogus"), unknownThemeTags("<bh:ok><bh:bogus>x</bh:bogus> <bh:player>y</bh:player>"),
                "the check above must fail for a tag the theme does not define");
    }

    @Test
    void everyKeyInTheBundlesIsUsedByTheCode() throws IOException {
        Set<String> literals = new TreeSet<>(COMPOSED_KEYS);
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = STRING_LITERAL.matcher(Files.readString(file));
                while (matcher.find()) {
                    literals.add(matcher.group(1));
                }
            }
        }
        // A key is used if the code names it or names the start of it ("duration.day" + ".one").
        Set<String> unused = new TreeSet<>();
        for (String key : load("blackhole_en.properties").stringPropertyNames()) {
            boolean used = literals.stream().anyMatch(literal -> key.equals(literal) || (literal.contains(".") && key.startsWith(literal)));
            if (!used && !UNREFERENCED_KEYS.contains(key)) {
                unused.add(key);
            }
        }

        assertTrue(unused.isEmpty(), "keys in the bundles that no code uses: " + unused);
    }
}
