package net.onelitefeather.blackhole.backend.banid.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.banid.BanIdCodec;
import net.onelitefeather.blackhole.backend.banid.BanIdCrypto;
import net.onelitefeather.blackhole.backend.banid.BanIdGenerator;
import net.onelitefeather.blackhole.backend.banid.BanIdStore;
import net.onelitefeather.blackhole.backend.banid.dto.BanIdDTO;
import net.onelitefeather.blackhole.backend.evidence.validation.PiiGuard;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentShortId;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Issues, looks up and reveals Ban IDs. The plain Ban ID exists in memory only, inside these methods
 * and in the {@link BanIdDTO.Response} of a reveal; nothing here logs, counts or publishes it.
 */
@Singleton
public class BanIdService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BanIdService.class);
    /** 2^40 candidates against a few thousand punishments: a second draw is already a rarity. */
    private static final int MAX_ATTEMPTS = 10;

    private final BanIdStore store;
    private final BanIdCrypto crypto;
    private final BanIdGenerator generator;
    private final PunishmentRepository punishments;

    public BanIdService(BanIdStore store, BanIdCrypto crypto, BanIdGenerator generator, PunishmentRepository punishments) {
        this.store = store;
        this.crypto = crypto;
        this.generator = generator;
        this.punishments = punishments;
    }

    /**
     * Gives a punishment its Ban ID if it has none. Never throws: a punishment must not fail because
     * of this, and {@code BanIdBackfill} issues the ones that were missed.
     *
     * @return whether this call issued the Ban ID
     */
    public boolean issue(String punishmentIdentifier) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                String candidate = this.generator.generate();
                if (this.store.legacyShortIdExists(candidate)) {
                    continue;
                }
                BanIdStore.Assignment result = this.store.assign(
                        punishmentIdentifier, this.crypto.hash(candidate), this.crypto.encrypt(candidate, punishmentIdentifier));
                switch (result) {
                    case ASSIGNED:
                        return true;
                    case UNCHANGED:
                        return false;
                    case COLLISION:
                        break;
                }
            }
            LOGGER.warn("No free Ban ID found for punishment {} after {} draws", punishmentIdentifier, MAX_ATTEMPTS);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not issue a Ban ID for punishment {} ({})", punishmentIdentifier, e.getClass().getSimpleName());
        }
        return false;
    }

    /** The stored form of a new Ban ID: the lookup hash and the ciphertext bound to the punishment. Never the plain value. */
    public record Material(byte[] hash, byte[] encrypted) {
    }

    /**
     * Draws a fresh Ban ID for a punishment without storing it, for a reissue that stores it together
     * with the rest of its change. A candidate equal to a legacy short id is discarded.
     *
     * @return the stored form, empty if no usable candidate was found
     */
    public Optional<Material> draw(String punishmentIdentifier) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = this.generator.generate();
            if (!this.store.legacyShortIdExists(candidate)) {
                return Optional.of(new Material(this.crypto.hash(candidate), this.crypto.encrypt(candidate, punishmentIdentifier)));
            }
        }
        return Optional.empty();
    }

    /**
     * The punishment a typed Ban ID belongs to.
     *
     * @param typed the Ban ID as typed, with or without {@code #}, in any case
     * @return the identifier, empty if the input is no Ban ID or nobody has it
     */
    public Optional<String> findIdentifier(String typed) {
        return BanIdCodec.normalize(typed).flatMap(banId -> this.store.identifierByHash(this.crypto.hash(banId)));
    }

    /** Whether the text contains an issued Ban ID or something shaped like one that is not a legacy short id. */
    public boolean containsBanId(String text) {
        for (String candidate : PiiGuard.banIdCandidates(text)) {
            if (!PiiGuard.isHexOnly(candidate) || this.store.identifierByHash(this.crypto.hash(candidate)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The current Ban ID of a punishment, for staff and the proxy.
     *
     * @param reference the full identifier, or a legacy short id when it matches exactly one punishment
     * @return the {@link BanIdDTO.Response}, or an error: 404 unknown or not issued yet, 409 ambiguous
     *         short id, 503 when the stored value cannot be read with the current key
     */
    public BanIdDTO reveal(String reference) {
        Resolved resolved = resolve(reference);
        if (resolved.error() != null) {
            return resolved.error();
        }
        String identifier = resolved.punishment().getIdentifier();
        Optional<byte[]> blob = this.store.encrypted(identifier);
        if (blob.isEmpty()) {
            return error(ErrorKind.NOT_FOUND, "this punishment has no Ban ID yet");
        }
        String banId = this.crypto.decrypt(blob.get(), identifier);
        if (banId == null) {
            LOGGER.error("The Ban ID of punishment {} cannot be decrypted; was BLACKHOLE_BANID_KEY changed?", identifier);
            return error(ErrorKind.UNAVAILABLE, "the Ban ID cannot be read with the configured key");
        }
        return new BanIdDTO.Response(banId);
    }

    private record Resolved(PunishmentEntity punishment, BanIdDTO.Error error) {
    }

    private Resolved resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return new Resolved(null, error(ErrorKind.VALIDATION, "a punishment is required"));
        }
        Optional<PunishmentEntity> byId = this.punishments.findById(reference.strip());
        if (byId.isPresent()) {
            return new Resolved(byId.get(), null);
        }
        Optional<String> key = PunishmentShortId.parse(reference);
        if (key.isEmpty()) {
            return new Resolved(null, error(ErrorKind.NOT_FOUND, "there is no such punishment"));
        }
        List<PunishmentEntity> matches = this.punishments.findByShortId(key.get());
        if (matches.isEmpty()) {
            return new Resolved(null, error(ErrorKind.NOT_FOUND, "there is no such punishment"));
        }
        if (matches.size() > 1) {
            String candidates = String.join(", ", matches.stream().map(PunishmentEntity::getIdentifier).toList());
            return new Resolved(null, error(ErrorKind.CONFLICT, "the short id " + PunishmentShortId.display(key.get())
                    + " matches several punishments, use the full identifier: " + candidates));
        }
        return new Resolved(matches.getFirst(), null);
    }

    private static BanIdDTO.Error error(ErrorKind kind, String message) {
        return new BanIdDTO.Error(message, kind);
    }
}
