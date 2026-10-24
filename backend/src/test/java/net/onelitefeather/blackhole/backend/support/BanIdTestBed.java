package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.banid.BanIdCrypto;
import net.onelitefeather.blackhole.backend.banid.BanIdGenerator;
import net.onelitefeather.blackhole.backend.banid.BanIdProperties;
import net.onelitefeather.blackhole.backend.banid.BanIdStore;
import net.onelitefeather.blackhole.backend.banid.service.BanIdService;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Ban ID service over an in-memory store with a fixed key: one fixture per test, no database.
 * The store behaves like the table: the first Ban ID of a punishment sticks, a hash is unique.
 */
public final class BanIdTestBed {

    public static final byte[] KEY = TestKeys.randomBytes();

    /** The columns {@code ban_id_hash} and {@code ban_id_enc} of the punishments, by punishment. */
    public static final class MemoryStore implements BanIdStore {

        private final Map<String, byte[]> hashes = new LinkedHashMap<>();
        private final Map<String, byte[]> encrypted = new LinkedHashMap<>();
        private final Set<String> known = new HashSet<>();
        private final Set<String> legacyShortIds = new HashSet<>();

        /** A punishment that exists but has no Ban ID yet. */
        public void givenPunishment(String identifier) {
            this.known.add(identifier);
        }

        public void givenLegacyShortId(String key) {
            this.legacyShortIds.add(key);
        }

        public Map<String, byte[]> hashes() {
            return this.hashes;
        }

        public Map<String, byte[]> encrypted() {
            return this.encrypted;
        }

        @Override
        public Assignment assign(String punishmentIdentifier, byte[] hash, byte[] encryptedBanId) {
            if (!this.known.contains(punishmentIdentifier) || this.hashes.containsKey(punishmentIdentifier)) {
                return Assignment.UNCHANGED;
            }
            if (this.hashes.values().stream().anyMatch(existing -> Arrays.equals(existing, hash))) {
                return Assignment.COLLISION;
            }
            this.hashes.put(punishmentIdentifier, hash);
            this.encrypted.put(punishmentIdentifier, encryptedBanId);
            return Assignment.ASSIGNED;
        }

        @Override
        public Optional<String> identifierByHash(byte[] hash) {
            return this.hashes.entrySet().stream().filter(e -> Arrays.equals(e.getValue(), hash)).map(Map.Entry::getKey).findFirst();
        }

        @Override
        public Optional<byte[]> encrypted(String punishmentIdentifier) {
            return Optional.ofNullable(this.encrypted.get(punishmentIdentifier));
        }

        @Override
        public boolean legacyShortIdExists(String shortIdKey) {
            return this.legacyShortIds.contains(shortIdKey);
        }

        @Override
        public List<String> withoutBanId(int limit) {
            return this.known.stream().filter(id -> !this.hashes.containsKey(id)).sorted().limit(limit).toList();
        }

        @Override
        public List<Held> heldAfter(String after, int limit) {
            return this.hashes.keySet().stream().filter(id -> after == null || id.compareTo(after) > 0).sorted().limit(limit)
                    .map(id -> new Held(id, this.encrypted.get(id), 1)).toList();
        }

        @Override
        public long countWithoutBanId() {
            return this.known.stream().filter(id -> !this.hashes.containsKey(id)).count();
        }
    }

    public final MemoryStore store = new MemoryStore();
    public final BanIdCrypto crypto = new BanIdCrypto(new BanIdProperties(Base64.getEncoder().encodeToString(KEY)));
    public final BanIdService service;

    public BanIdTestBed(PunishmentRepository punishments) {
        this(punishments, new BanIdGenerator());
    }

    public BanIdTestBed(PunishmentRepository punishments, BanIdGenerator generator) {
        this.service = new BanIdService(this.store, this.crypto, generator, punishments);
    }

    /** Issues the Ban ID of a punishment and returns it in clear, by decrypting what the store holds. */
    public String issueFor(String identifier) {
        this.store.givenPunishment(identifier);
        this.service.issue(identifier);
        return this.crypto.decrypt(this.store.encrypted().get(identifier), identifier);
    }
}
