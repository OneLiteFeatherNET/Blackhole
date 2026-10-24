package net.onelitefeather.blackhole.backend.publicapi.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.publicapi.PlayerScope;
import net.onelitefeather.blackhole.backend.publicapi.PlayerSessionProperties;
import net.onelitefeather.blackhole.backend.publicapi.PlayerSessionStore;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorKind;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicSessionDTO;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.blackhole.backend.security.PlayerSessionRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Logs a player in with the Ban ID, checks a session token on every request and ends sessions. The Ban ID
 * exists here as a method argument only: it is looked up and dropped, never stored, logged or counted. The
 * token is shown once; only its SHA-256 is stored. Every time is read from the injected clock.
 */
@Singleton
public class PlayerSessionService {

    /** The counter of login attempts; its only tag is {@code result} ({@code success}, {@code limited}, {@code failure}). */
    public static final String LOGIN_METER = "blackhole.public.login";

    /** The one answer to every login that is not accepted: no hint which part was wrong. */
    static final PublicSessionDTO.Error INVALID_BAN_ID = new PublicSessionDTO.Error("INVALID_BAN_ID", null, PublicErrorKind.UNAUTHORIZED);

    /** The one login failure, for a caller that fails before a login can run (an unreadable body). */
    public static PublicSessionDTO.Error invalidBanId() {
        return INVALID_BAN_ID;
    }

    private static final int TOKEN_BYTES = 32;
    private static final int CSRF_BYTES = 24;

    private final BanIdService banIds;
    private final CaseReader cases;
    private final PlayerSessionStore store;
    private final PlayerSessionProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final SecureRandom random;

    @Inject
    public PlayerSessionService(
            BanIdService banIds, CaseReader cases, PlayerSessionStore store, PlayerSessionProperties properties, Clock clock, MeterRegistry meters
    ) {
        this(banIds, cases, store, properties, clock, meters, new SecureRandom());
    }

    /** For a test that seeds the random source. */
    public PlayerSessionService(
            BanIdService banIds, CaseReader cases, PlayerSessionStore store, PlayerSessionProperties properties, Clock clock, MeterRegistry meters,
            SecureRandom random
    ) {
        this.banIds = banIds;
        this.cases = cases;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.random = random;
    }

    /**
     * Opens a session for a typed Ban ID.
     *
     * @return the {@link PublicSessionDTO.Created} (scope {@code LIMITED} for a leaked Ban ID), or the one
     *         {@link PublicSessionDTO.Error} that every other input gets
     */
    public PublicSessionDTO login(@Nullable String typedBanId) {
        Optional<String> punishment = this.banIds.findIdentifier(typedBanId);
        if (punishment.isEmpty()) {
            count("failure");
            return INVALID_BAN_ID;
        }
        String identifier = punishment.get();
        boolean leaked = this.cases.find(identifier).filter(CaseReader.CaseRow::leaked).isPresent();
        PlayerScope scope = leaked ? PlayerScope.LIMITED : PlayerScope.FULL;
        String token = PlayerSessionRule.TOKEN_PREFIX + randomText(TOKEN_BYTES);
        String csrf = randomText(CSRF_BYTES);
        long now = this.clock.millis();
        long expiresAt = now + this.properties.absolute().toMillis();
        this.store.insert(hash(token), identifier, scope, csrf, now, expiresAt);
        count(leaked ? "limited" : "success");
        return new PublicSessionDTO.Created(token, csrf, Instant.ofEpochMilli(expiresAt).toString(), scope);
    }

    /** The session of a token, if it is live; the look-up slides the idle limit. */
    public Optional<PlayerSession> authenticate(@Nullable String token) {
        if (token == null || !token.startsWith(PlayerSessionRule.TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String tokenHash = hash(token);
        return this.store.touch(tokenHash, this.clock.millis(), this.properties.idle().toMillis())
                .map(live -> new PlayerSession(tokenHash, live.punishmentIdentifier(), live.scope(), live.csrf(), live.expiresAt()));
    }

    /** The session behind a request, for the portal's bootstrap. */
    public PublicSessionDTO.Current current(PlayerSession session) {
        return new PublicSessionDTO.Current(session.csrf(), Instant.ofEpochMilli(session.expiresAt()).toString(), session.scope());
    }

    /** Ends the session; the token stops working at once. */
    public void logout(PlayerSession session) {
        this.store.delete(session.tokenHash());
    }

    /** Deletes the sessions past their idle or absolute limit; how many. */
    public int sweep() {
        return this.store.sweep(this.clock.millis(), this.properties.idle().toMillis());
    }

    private void count(String result) {
        this.meters.counter(LOGIN_METER, "result", result).increment();
    }

    private String randomText(int bytes) {
        byte[] value = new byte[bytes];
        this.random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
