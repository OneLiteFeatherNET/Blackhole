package net.onelitefeather.blackhole.backend.clientrule.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleAction;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleKind;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleScope;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleSpec;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.time.Instant;
import java.util.List;

/**
 * Wire contract of {@code /client-rule}. A rule is identified by its client-chosen key, so unlike a
 * server-assigned identifier the key is present on create as well. Brand, channel and mod rules
 * match values the client chooses and can fake; see the {@code client-rules} capability.
 */
@Schema(description = "A client rule: matches the brand, channels, mods, protocol version or a command and applies an action")
@Serdeable
public sealed interface ClientRuleDTO {

    @Schema(name = "ClientRuleCreateRequest", description = "A new rule, enabled. The action defaults to NOTIFY and the scope to GLOBAL")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @NonNull @NotBlank String key,
            @NonNull @NotBlank String displayName,
            @NonNull ClientRuleKind kind,
            @Nullable List<String> patterns,
            @Nullable Integer protocolMin,
            @Nullable Integer protocolMax,
            @Nullable Boolean negate,
            @Nullable ClientRuleScope scope,
            @Nullable List<String> sectionKeys,
            @Nullable ServerTargetKind targetKind,
            @Nullable List<String> servers,
            @Nullable ClientRuleAction action,
            @Nullable String denyMessage,
            @Nullable String templateKey,
            @Nullable Boolean acknowledgedSpoofable,
            @Nullable Boolean hideCommand
    ) implements ClientRuleDTO {

        public ClientRuleSpec toSpec() {
            return new ClientRuleSpec(this.key, this.displayName, this.kind, true, this.patterns, this.protocolMin, this.protocolMax,
                    Boolean.TRUE.equals(this.negate), this.scope, this.sectionKeys, this.targetKind, this.servers, this.action,
                    this.denyMessage, this.templateKey, Boolean.TRUE.equals(this.acknowledgedSpoofable), Boolean.TRUE.equals(this.hideCommand));
        }
    }

    @Schema(name = "ClientRuleUpdateRequest", description = "Replaces every editable field of the rule named by key, including whether it is enabled")
    @Serdeable
    @ReflectiveAccess
    record UpdateRequest(
            @NonNull @NotBlank String key,
            @NonNull @NotBlank String displayName,
            @NonNull ClientRuleKind kind,
            boolean enabled,
            @Nullable List<String> patterns,
            @Nullable Integer protocolMin,
            @Nullable Integer protocolMax,
            @Nullable Boolean negate,
            @Nullable ClientRuleScope scope,
            @Nullable List<String> sectionKeys,
            @Nullable ServerTargetKind targetKind,
            @Nullable List<String> servers,
            @Nullable ClientRuleAction action,
            @Nullable String denyMessage,
            @Nullable String templateKey,
            @Nullable Boolean acknowledgedSpoofable,
            @Nullable Boolean hideCommand
    ) implements ClientRuleDTO {

        public ClientRuleSpec toSpec() {
            return new ClientRuleSpec(this.key, this.displayName, this.kind, this.enabled, this.patterns, this.protocolMin, this.protocolMax,
                    Boolean.TRUE.equals(this.negate), this.scope, this.sectionKeys, this.targetKind, this.servers, this.action,
                    this.denyMessage, this.templateKey, Boolean.TRUE.equals(this.acknowledgedSpoofable), Boolean.TRUE.equals(this.hideCommand));
        }
    }

    @Schema(name = "ClientRuleResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull String key,
            @NonNull String displayName,
            @NonNull ClientRuleKind kind,
            boolean enabled,
            @NonNull List<String> patterns,
            @Nullable Integer protocolMin,
            @Nullable Integer protocolMax,
            boolean negate,
            @NonNull ClientRuleScope scope,
            @NonNull List<String> sectionKeys,
            @NonNull ServerTargetKind targetKind,
            @NonNull List<String> servers,
            @NonNull ClientRuleAction action,
            @Nullable String denyMessage,
            @Nullable String templateKey,
            boolean acknowledgedSpoofable,
            boolean hideCommand,
            @Schema(description = "Total hits ever recorded for the rule; kept after the single hits expire") long hitCount,
            @NonNull Instant createdAt,
            @NonNull Instant updatedAt
    ) implements ClientRuleDTO {
    }

    @Schema(name = "ClientRuleError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull ErrorKind kind) implements ClientRuleDTO, ErrorResponse {
    }
}
