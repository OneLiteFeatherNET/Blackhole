package net.onelitefeather.blackhole.api.profile;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;

record PunishProfileDTO(
        @NotNull String owner,
        @NotNull Optional<PunishEntry> activeChatBan,
        @NotNull Optional<PunishEntry> activeBan,
        @NotNull List<PunishEntry> history,
        @NotNull Map<String, Object> metaData
) implements PunishProfile {

    @Override
    public void addMetaData(@NotNull String key, @NotNull Object value) {
        this.metaData.put(key, value);
    }

    @Override
    public void removeMetaData(@NotNull String key) {
        this.metaData.remove(key);
    }

    @Override
    public boolean hasMetaData(@NotNull String key) {
        return this.metaData.containsKey(key);
    }

    @Override
    public @NotNull Optional<@Nullable Object> getMetaData(@NotNull String key) {
        return Optional.ofNullable(this.metaData.get(key));
    }

    @Override
    public @NotNull List<PunishEntry> historyByType(@NotNull PunishType type, @NotNull Comparator<PunishEntry> comparator) {
        return this.history.stream()
                .filter(entry -> entry.type().ordinal() == type.ordinal())
                .sorted(comparator)
                .toList();
    }

    @Override
    public @NotNull @UnmodifiableView List<PunishEntry> history(@NotNull Comparator<PunishEntry> comparator) {
        return this.history.stream().sorted(comparator).toList();
    }

    @Override
    public @NotNull @UnmodifiableView Map<String, Object> metaData() {
        return Collections.unmodifiableMap(this.metaData);
    }

    @Override
    public long creationDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_CREATION_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long updateDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_UPDATE_DATE)).map(Long.class::cast).orElseThrow();
    }
}
