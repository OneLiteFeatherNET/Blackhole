package net.onelitefeather.blackhole.backend.publicbans.auth;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The named ban-list clients, read once at startup: each name in {@code BLACKHOLE_BAN_LIST_CLIENTS} needs a file of the same
 * name in {@code BLACKHOLE_BAN_LIST_SECRETS_DIR} holding its Argon2id PHC hash. A missing file stops the start outside the
 * {@code dev} and {@code test} environments and is skipped with a warning inside them. Nothing here logs a hash.
 */
@Singleton
public class BanListClientRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BanListClientRegistry.class);
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of("dev", "test");

    private final Map<String, ClientHashVerifier> verifiers;

    @Inject
    public BanListClientRegistry(Environment environment,
                                 @Value("${blackhole.ban-list.clients:}") String clients,
                                 @Value("${blackhole.ban-list.secrets-dir:/etc/blackhole/ban-list}") String secretsDir) {
        this(clients, Path.of(secretsDir), environment.getActiveNames());
    }

    BanListClientRegistry(String clients, Path secretsDir, Set<String> activeEnvironments) {
        boolean local = activeEnvironments.stream().anyMatch(LOCAL_ENVIRONMENTS::contains);
        this.verifiers = new LinkedHashMap<>();
        if (clients == null || clients.isBlank()) {
            return;
        }
        for (String raw : clients.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("a ban-list client name must match [a-z0-9-]{1,32}; BLACKHOLE_BAN_LIST_CLIENTS has one that does not");
            }
            register(name, secretsDir.resolve(name), local);
        }
    }

    /** The verifier of each registered client, by name. */
    public Map<String, ClientHashVerifier> verifiers() {
        return this.verifiers;
    }

    private void register(String name, Path file, boolean local) {
        if (!Files.isRegularFile(file)) {
            if (local) {
                LOG.warn("ban-list client {} has no hash file at {} and is skipped in this environment", name, file);
                return;
            }
            throw new IllegalStateException("ban-list client " + name + " has no hash file at " + file
                    + "; mount the client's Argon2id hash there");
        }
        try {
            this.verifiers.put(name, Argon2ClientHashVerifier.of(Files.readString(file).strip()));
        } catch (IOException e) {
            throw new IllegalStateException("ban-list client " + name + " has an unreadable hash file at " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ban-list client " + name + " has a malformed hash file at " + file
                    + ": " + e.getMessage());
        }
    }
}
