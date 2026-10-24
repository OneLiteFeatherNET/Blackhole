package net.onelitefeather.blackhole.velocity.clientrule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * What staff are told about a client rule hit. It carries the player's name, so it only ever travels over
 * the transient Redis pub/sub channel (nothing is stored) and is never sent to the backend. It holds no
 * client trait either: the rule's display name already says what matched.
 *
 * @param rule     the rule's display name
 * @param player   the player's name
 * @param kind     the rule kind ({@code BRAND}, {@code CHANNEL}, ...)
 * @param action   the rule action ({@code NOTIFY}, {@code DENY}, {@code TEMPLATE})
 * @param enforced whether the proxy refused the connection or command because of it
 */
public record ClientRuleNotice(@NotNull String rule, @NotNull String player, @NotNull String kind, @NotNull String action, boolean enforced) {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** @return the wire form */
    public static @NotNull String encode(@NotNull ClientRuleNotice notice) {
        try {
            return MAPPER.writeValueAsString(notice);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A client rule notice could not be encoded", e);
        }
    }

    /** @return the notice, empty if the text is not one */
    public static @NotNull Optional<ClientRuleNotice> decode(@Nullable String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            ClientRuleNotice notice = MAPPER.readValue(json, ClientRuleNotice.class);
            boolean complete = notice.rule() != null && notice.player() != null && notice.kind() != null && notice.action() != null;
            return complete ? Optional.of(notice) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
