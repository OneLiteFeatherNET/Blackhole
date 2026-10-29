package net.onelitefeather.blackhole.backend.publicbans.auth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * Authenticates a Basic credential against the named ban-list clients. Every failure cause is an {@link Outcome} without a
 * client; an unknown name still runs one verification against the dummy hash, so the cost of a failed request does not
 * reveal whether the name exists. Passwords are never stored, logged or returned.
 */
@Singleton
public class BanListAuthenticator {

    /** Why a request was refused; {@link #OK} when a client was authenticated. */
    public enum Reason {
        OK, MISSING, MALFORMED, UNKNOWN_CLIENT, BAD_PASSWORD
    }

    /**
     * @param client the authenticated client name, or {@code null}
     * @param reason the reason class
     */
    public record Outcome(String client, Reason reason) {
    }

    private final Map<String, ClientHashVerifier> clients;
    private final ClientHashVerifier dummy;

    @Inject
    public BanListAuthenticator(BanListClientRegistry registry) {
        this(registry.verifiers(), Argon2ClientHashVerifier.dummy());
    }

    BanListAuthenticator(Map<String, ClientHashVerifier> clients, ClientHashVerifier dummy) {
        this.clients = clients;
        this.dummy = dummy;
    }

    /** @param authorization the raw {@code Authorization} header, or {@code null} */
    public Outcome authenticate(String authorization) {
        BasicAuthHeader header = BasicAuthHeader.parse(authorization);
        switch (header.kind()) {
            case MISSING -> {
                return new Outcome(null, Reason.MISSING);
            }
            case MALFORMED -> {
                return new Outcome(null, Reason.MALFORMED);
            }
            default -> {
                // a valid pair is checked below
            }
        }
        ClientHashVerifier verifier = this.clients.get(header.user());
        if (verifier == null) {
            this.dummy.matches(header.password());
            return new Outcome(null, Reason.UNKNOWN_CLIENT);
        }
        if (!verifier.matches(header.password())) {
            return new Outcome(null, Reason.BAD_PASSWORD);
        }
        return new Outcome(header.user(), Reason.OK);
    }
}
