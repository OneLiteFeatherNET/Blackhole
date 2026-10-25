package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.profile.ProfileSlots;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The semantics of the SQL row operations of {@link ProfileSlots} over in-memory repositories, for
 * unit tests. Like the real thing it touches only the named slot, history and session fields.
 * {@link #failNextOccupy} simulates a database failure while placing a punishment.
 */
public final class InMemoryProfileSlots implements ProfileSlots {

    private final PunishmentProfileRepository profiles;
    private final PunishmentRepository punishments;
    private RuntimeException nextOccupyFailure;

    public InMemoryProfileSlots(PunishmentProfileRepository profiles, PunishmentRepository punishments) {
        this.profiles = profiles;
        this.punishments = punishments;
    }

    public void failNextOccupy(RuntimeException failure) {
        this.nextOccupyFailure = failure;
    }

    @Override
    public synchronized Placement occupy(String owner, String sectionKey, String punishmentIdentifier) {
        if (this.nextOccupyFailure != null) {
            RuntimeException failure = this.nextOccupyFailure;
            this.nextOccupyFailure = null;
            throw failure;
        }
        PunishmentProfileEntity profile = this.profiles.findById(owner).orElse(null);
        boolean created = profile == null;
        if (created) {
            profile = new PunishmentProfileEntity(owner, new HashMap<>(), new ArrayList<>(), new HashMap<>());
            this.profiles.save(profile);
        }
        PunishmentEntity held = profile.getActive().get(sectionKey);
        if (held != null && held.isPermanent()) {
            return new Placement(false, created, Optional.empty());
        }
        PunishmentEntity placed = this.punishments.findById(punishmentIdentifier).orElseThrow();
        profile.getActive().put(sectionKey, placed);
        if (held != null) {
            profile.getHistory().add(held);
        }
        return new Placement(true, created, Optional.ofNullable(held).map(PunishmentEntity::getIdentifier));
    }

    @Override
    public synchronized boolean clearIf(String owner, String sectionKey, String punishmentIdentifier) {
        PunishmentProfileEntity profile = this.profiles.findById(owner).orElse(null);
        if (profile == null) {
            return false;
        }
        PunishmentEntity held = profile.getActive().get(sectionKey);
        if (held == null || !held.getIdentifier().equals(punishmentIdentifier)) {
            return false;
        }
        profile.getActive().remove(sectionKey);
        profile.getHistory().add(held);
        return true;
    }

    @Override
    public synchronized void appendHistory(String owner, String punishmentIdentifier) {
        PunishmentProfileEntity profile = this.profiles.findById(owner).orElse(null);
        if (profile == null) {
            profile = new PunishmentProfileEntity(owner, new HashMap<>(), new ArrayList<>(), new HashMap<>());
            this.profiles.save(profile);
        }
        PunishmentEntity punishment = this.punishments.findById(punishmentIdentifier).orElseThrow();
        if (profile.getHistory().stream().noneMatch(entry -> entry.getIdentifier().equals(punishmentIdentifier))) {
            profile.getHistory().add(punishment);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void mergeSessionInfo(String owner, Map<String, Object> fields) {
        PunishmentProfileEntity profile = this.profiles.findById(owner).orElse(null);
        if (profile == null) {
            profile = new PunishmentProfileEntity(owner, new HashMap<>(), new ArrayList<>(), new HashMap<>());
            this.profiles.save(profile);
        }
        ((Map<String, Object>) profile.getMetaData().computeIfAbsent("sessionInfo", key -> new HashMap<String, Object>())).putAll(fields);
    }
}
