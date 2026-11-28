package net.onelitefeather.blackhole.backend.firewall;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The firewall feed clients, read once at start-up. Each name in {@code BLACKHOLE_FIREWALL_FEED_CLIENTS} has a file of the
 * same name in {@code BLACKHOLE_FIREWALL_FEED_SECRETS_DIR} holding the lowercase SHA-256 hex of its token; a second file
 * {@code <name>.next} holds the hash of the next token, and both are accepted until the old file is removed. The clear token
 * is never stored here. A missing or malformed file stops the start outside {@code dev} and {@code test}.
 */
@Singleton
public class FeedTokenRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(FeedTokenRegistry.class);
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of("dev", "test");

    private record Hash(String client, byte[] digest) {
    }

    private final List<Hash> hashes = new ArrayList<>();
    private final List<String> clients = new ArrayList<>();

    @Inject
    public FeedTokenRegistry(Environment environment,
                             @Value("${blackhole.firewall.feed.clients:}") String clients,
                             @Value("${blackhole.firewall.feed.secrets-dir:/etc/blackhole/firewall-feed}") String secretsDir) {
        this(clients, Path.of(secretsDir), environment.getActiveNames());
    }

    FeedTokenRegistry(String clients, Path secretsDir, Set<String> activeEnvironments) {
        boolean local = activeEnvironments.stream().anyMatch(LOCAL_ENVIRONMENTS::contains);
        if (clients == null || clients.isBlank()) {
            return;
        }
        for (String raw : clients.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("a firewall feed client name must match [a-z0-9-]{1,32}; "
                        + "BLACKHOLE_FIREWALL_FEED_CLIENTS has one that does not");
            }
            this.clients.add(name);
            register(name, secretsDir.resolve(name), local);
            register(name, secretsDir.resolve(name + ".next"), local);
        }
    }

    /**
     * @return the client whose token hashes to a configured hash, or empty. Every configured hash is compared in constant
     * time and the loop never stops early, so the time does not reveal which client matched.
     */
    public Optional<String> clientOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        byte[] digest = sha256(token);
        String matched = null;
        for (Hash hash : this.hashes) {
            if (MessageDigest.isEqual(digest, hash.digest())) {
                matched = hash.client();
            }
        }
        return Optional.ofNullable(matched);
    }

    /** @return the configured client names, in configuration order */
    public List<String> clientNames() {
        return List.copyOf(this.clients);
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void register(String client, Path file, boolean local) {
        if (!Files.isRegularFile(file)) {
            if (file.getFileName().toString().endsWith(".next")) {
                return;
            }
            if (local) {
                LOG.warn("firewall feed client {} has no hash file at {} and is skipped in this environment", client, file);
                return;
            }
            throw new IllegalStateException("firewall feed client " + client + " has no hash file at " + file
                    + "; write the SHA-256 hex of its token there");
        }
        String text;
        try {
            text = Files.readString(file).strip();
        } catch (IOException e) {
            throw new IllegalStateException("firewall feed client " + client + " has an unreadable hash file at " + file, e);
        }
        if (!HASH.matcher(text).matches()) {
            if (local) {
                LOG.warn("firewall feed client {} has a malformed hash file at {} and is skipped in this environment", client, file);
                return;
            }
            throw new IllegalStateException("firewall feed client " + client + " has a malformed hash file at " + file
                    + "; it must hold 64 lowercase hex characters");
        }
        this.hashes.add(new Hash(client, HexFormat.of().parseHex(text)));
    }
}
