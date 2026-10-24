package net.onelitefeather.blackhole.discord;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core never imports JDA: only the {@code gateway} package may. Everything above it is tested with fakes behind
 * {@code DiscordGateway}, which is only possible while this holds. Reads the compiled classes, so a transitive use through a
 * signature, a field or a constant is caught too.
 */
class ArchitectureTest {

    private static final String ROOT = "net/onelitefeather/blackhole/discord/";

    private static Path mainClasses() throws URISyntaxException {
        return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void no_class_outside_the_gateway_package_references_jda() throws IOException, URISyntaxException {
        Path root = mainClasses();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root.resolve(ROOT))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(ROOT + "gateway/")) {
                    continue;
                }
                scanned++;
                String bytecode = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (bytecode.contains("net/dv8tion")) {
                    offenders.add(relative);
                }
            }
        }
        assertTrue(scanned > 40, "the scan found the core classes (" + scanned + ")");
        assertEquals(List.of(), offenders, "these classes depend on JDA outside the gateway package");
    }

    @Test
    void the_scan_would_notice_a_dependency_the_gateway_adapter_has_one() throws IOException, URISyntaxException {
        Path adapter = mainClasses().resolve(ROOT + "gateway/JdaGateway.class");

        String bytecode = new String(Files.readAllBytes(adapter), StandardCharsets.ISO_8859_1);

        assertTrue(bytecode.contains("net/dv8tion"), "the adapter is where JDA is used");
        assertFalse(Files.readString(Path.of(mainClasses().toString(), ROOT + "gateway/DiscordGateway.class"), StandardCharsets.ISO_8859_1).contains("net/dv8tion"),
                "and the interface it implements is free of it");
    }
}
