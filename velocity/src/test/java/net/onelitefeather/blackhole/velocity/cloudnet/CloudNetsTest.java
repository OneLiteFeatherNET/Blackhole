package net.onelitefeather.blackhole.velocity.cloudnet;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudNetsTest {

    @Test
    void offNeverProbes() {
        ClassLoader failing = new ClassLoader() {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("no class may be loaded when the integration is off: " + name);
            }
        };

        assertInstanceOf(NoCloudNetView.class, CloudNets.detect("off", failing, NOPLogger.NOP_LOGGER));
    }

    @Test
    void autoWithoutTheDriverClassesStartsWithoutCloudNet() {
        CloudNetView view = CloudNets.detect("auto", CloudNetsTest.class.getClassLoader(), NOPLogger.NOP_LOGGER);

        assertInstanceOf(NoCloudNetView.class, view);
        assertFalse(view.available());
        assertTrue(view.facts("BedWars-1").isEmpty());
    }

    @Test
    void onlyTheDriverViewImportsCloudNet() throws IOException {
        Path main = Path.of("src/main/java");
        List<String> importers;
        try (Stream<Path> files = Files.walk(main)) {
            importers = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> read(file).contains("import eu.cloudnetservice."))
                    .map(file -> file.getFileName().toString())
                    .toList();
        }

        assertEquals(List.of("DriverCloudNetView.java"), importers);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
