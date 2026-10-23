package net.onelitefeather.blackhole.backend.punishment.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An applied punishment as it appears in profiles and apply results.
 *
 * @param identifier      the identifier of the punishment
 * @param shortId         the short ID players quote, {@code #} and eight upper case hex characters
 * @param source          the source of the punishment: the staff member's {@code oid} for punishments written with a
 *                        token, the delegated in-game UUID for the proxy's, or whatever was stored before SSO
 * @param recordedByOid   the {@code oid} of the verified principal that wrote it, {@code null} for punishments written
 *                        before SSO
 * @param verified        whether {@code recordedByOid} is present: {@code false} marks a legacy, unverified attribution
 * @param section         the section it belongs to
 * @param scope           the optional scope (e.g. event/community) it is restricted to, or {@code null}
 * @param permanent       whether it is a permanent punishment issued by the system's escalation
 * @param lifted          whether it was revoked or lifted by a granted appeal
 * @param createdAt       creation time in epoch millis
 * @param escalationGroup the escalation group a template punishment counted towards, else {@code null}
 * @param stepIndex       the zero-based escalation step that was applied, else {@code null}
 * @param stepCount       how many steps the template had when it was applied, else {@code null}
 * @param eloCost         the ELO deducted when it was applied, else {@code null}
 * @param template        the template it came from, or {@code null} for system punishments
 * @param evidenceCount   how many valid evidence items the punishment has - the only thing about evidence a
 *                        player may learn; {@code null} where it was not computed (profile listings), use
 *                        {@code GET /punishment/{reference}/evidence/count} there
 * @param metaData        the metadata of the punishment
 * @author theEvilReaper
 * @version 2.0.0
 * @since 1.0.0
 */
@Serdeable
@ReflectiveAccess
public record PunishEntryDTO(
        String identifier,
        String shortId,
        UUID source,
        @Nullable UUID recordedByOid,
        boolean verified,
        String section,
        @Nullable String scope,
        boolean permanent,
        boolean lifted,
        @Nullable Long createdAt,
        @Nullable String escalationGroup,
        @Nullable Integer stepIndex,
        @Nullable Integer stepCount,
        @Nullable Integer eloCost,
        @Nullable PunishTemplateDTO.Response template,
        @Nullable Long evidenceCount,
        Map<String, Object> metaData
) implements Metadata, Expirable {

    /** This entry with the number of valid evidence items filled in. */
    public PunishEntryDTO withEvidenceCount(long count) {
        return new PunishEntryDTO(this.identifier, this.shortId, this.source, this.recordedByOid, this.verified, this.section, this.scope, this.permanent,
                this.lifted, this.createdAt, this.escalationGroup, this.stepIndex, this.stepCount, this.eloCost,
                this.template, count, this.metaData);
    }

    @Override
    public void addMetaData(String key, Object value) {
        this.metaData.put(key, value);
    }

    @Override
    public void removeMetaData(String key) {
        this.metaData.remove(key);
    }

    @Override
    public boolean hasMetaData(String key) {
        return this.metaData.containsKey(key);
    }

    @Override
    public Optional<Object> getMetaData(String key) {
        return Optional.ofNullable(this.metaData.get(key));
    }

    @Override
    public long creationDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_CREATION_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long updateDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_UPDATE_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long expirationDate() {
        return Optional.ofNullable(this.metaData.get(Expirable.META_DATA_KEY_EXPIRATION_DATE)).map(Long.class::cast).orElseThrow();
    }
}
