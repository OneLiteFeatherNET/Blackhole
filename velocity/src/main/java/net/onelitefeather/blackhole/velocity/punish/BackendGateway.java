package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.KickAccepted;
import net.onelitefeather.blackhole.client.model.KickResponse;
import net.onelitefeather.blackhole.client.model.KickScope;
import net.onelitefeather.blackhole.client.model.NameHistoryOwner;
import net.onelitefeather.blackhole.client.model.NameHistoryPage;
import org.jetbrains.annotations.Nullable;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.client.model.ReportFeedbackResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.client.model.WarningResponse;
import net.onelitefeather.blackhole.velocity.evidence.ChatSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The backend calls the staff commands need, in the proxy's terms. The commands and
 * {@link PunishService} depend on this interface only, so their decisions can be tested with a stub.
 */
public interface BackendGateway {

    /**
     * @return every punishment template (from the shared cache when it holds a list, otherwise the API)
     */
    List<PunishTemplateResponse> templates() throws ApiException;

    /**
     * Applies the template with the given key to the owner.
     *
     * @param playerUuid            the player's raw UUID, or null when unknown; sent next to the owner so the
     *                              backend can list the punishment on the public ban list
     * @param evidenceCorrelationId the key chat evidence was posted under ahead of the punishment, or null
     * @param warningOnly           whether the step the ladder reaches must be a warning (the backend refuses
     *                              the apply with 422 otherwise, and applies nothing)
     */
    PunishmentApplyResponse apply(String owner, UUID playerUuid, String templateKey, UUID source, String evidenceCorrelationId, boolean warningOnly) throws ApiException;

    /**
     * Posts the chat snapshot as {@code CHAT_LOG} evidence that precedes its punishment, to be claimed
     * by the punishment that carries the same correlation id. Asynchronous: the caller bounds the wait.
     */
    CompletableFuture<Void> postChatEvidence(String owner, String correlationId, ChatSnapshot snapshot, String sourceSystem, UUID source);

    /**
     * Counts the valid evidence items of a punishment. Asynchronous: the caller bounds the wait.
     *
     * @param punishment the punishment's identifier or short id
     */
    CompletableFuture<Long> countEvidence(String punishment);

    /**
     * The Ban ID of a punishment, the credential for the appeal portal. Asynchronous: the caller bounds
     * the wait. Never log the answer.
     *
     * @param punishment the punishment's identifier
     */
    CompletableFuture<String> revealBanId(String punishment);

    /**
     * Revokes the owner's active punishment in the section.
     */
    PunishmentRevokeResponse revoke(String owner, String section, UUID source) throws ApiException;

    /**
     * @return the owner's punishment profile; empty if they have none yet
     */
    Optional<PunishProfileDTO> profile(String owner) throws ApiException;

    /**
     * The live name lookup: the account that uses the name now.
     *
     * @return the account, empty when no account has the name
     */
    Optional<TargetResolver.Known> resolvePlayer(String name) throws ApiException;

    /**
     * @param owner the player's hashed owner, never the raw UUID
     * @param size  the most names to return
     * @return the newest names the owner used, newest last seen first
     */
    NameHistoryPage nameHistory(String owner, int size) throws ApiException;

    /**
     * The old-name lookup: the accounts that used the exact name, ignoring case.
     *
     * @return at most 20 accounts, newest last seen first; empty when none used it
     */
    List<NameHistoryOwner> nameOwners(String name) throws ApiException;

    /**
     * @return the owner's score in every section
     */
    EloProfileResponse elo(String owner) throws ApiException;

    /**
     * Binds the player to the staff member the code was issued to. The backend normalises the code, so it
     * is passed on as typed.
     */
    StaffLinkConfirmResponse confirmStaffLink(String code, UUID player) throws ApiException;

    /**
     * @return whether the Minecraft account is linked, and to whom
     */
    StaffLinkLookupResponse lookupStaffLink(UUID player) throws ApiException;

    /**
     * Posts the reported player's chat snapshot as {@code CHAT_LOG} evidence under the reported owner. It
     * is attributed to the proxy only, never to the reporting player. Asynchronous: the caller bounds the wait.
     *
     * @return the id of the stored evidence item
     */
    CompletableFuture<UUID> postReportChatEvidence(String reported, String correlationId, ChatSnapshot snapshot, String sourceSystem);

    /**
     * Files a player report; the backend answers HTTP 429 ({@link ApiException#getCode()}) when its report
     * limiter refuses it.
     *
     * @param description the reporter's text, or null
     * @param evidence    the evidence items the report references
     * @param metaData    the report metadata, e.g. the players' names
     */
    ReportDTO submitReport(String reporter, String reported, ReportCategory category, String description, List<UUID> evidence, Map<String, Object> metaData) throws ApiException;

    /**
     * Reads one report, to render the staff notice of a {@code report.created} event.
     */
    ReportDTO report(UUID identifier) throws ApiException;

    /**
     * @return the messages about the outcome of the owner's resolved reports that have not been shown
     * yet, oldest first
     */
    List<ReportFeedbackResponse> reportFeedback(String owner) throws ApiException;

    /** Tells the backend that a feedback message was shown, so it is not delivered again. */
    void acknowledgeReportFeedback(UUID identifier) throws ApiException;

    /**
     * @param owner the player's hashed owner, never the raw UUID
     * @return a one-time code to link the player's Discord account
     */
    DiscordLinkCodeResponse issueDiscordLinkCode(String owner) throws ApiException;

    /**
     * Kicks one player. The answer is immediate: nothing is delivered yet, poll {@link #kickStatus}.
     *
     * @param idempotencyKey makes a repeat answer the same kick
     * @param target         the server name or section key, null for {@code NETWORK}
     */
    KickAccepted kick(String idempotencyKey, String owner, KickScope scope, @Nullable String target, String reason) throws ApiException;

    /**
     * Kicks everybody from a server or section, as one audited action.
     *
     * @param scope {@code SERVER} or {@code SECTION}
     */
    KickAccepted kickMass(String idempotencyKey, KickScope scope, String target, String reason) throws ApiException;

    /**
     * Reads a kick (or mass kick) with its derived status. The proxy also reads the reason here before it
     * shows the kick screen.
     */
    KickResponse kickStatus(String id) throws ApiException;

    /**
     * Reports what this proxy did about a kick, over HTTP with the proxy's own token: the proxy is
     * subscribe-only on the event bus and never publishes. Idempotent per kick and proxy.
     *
     * @param found whether the player was connected here (a mass kick: whether anybody was on the target)
     * @param moved whether the player was moved or disconnected (a mass kick: whether at least one was)
     */
    void acknowledgeKick(String id, String proxyId, boolean found, boolean moved) throws ApiException;

    /**
     * Reports whether this proxy has SignedVelocity loaded; the backend shows staff a banner while it is
     * missing. Replaces the proxy's previous report.
     *
     * @param version the SignedVelocity version, null if absent or unknown
     */
    void reportProxyCapabilities(String proxyId, boolean present, String version) throws ApiException;

    /**
     * Reads one warning: its reason, when it was given and whether the player confirmed it. The proxy reads the
     * reason here before it shows the warning in game.
     *
     * @throws ApiException with code 404 when there is no such warning
     */
    WarningResponse warning(String id) throws ApiException;

    /**
     * @param owner the player's hashed owner, never the raw UUID
     * @return the warnings of the owner that the player has not confirmed yet, oldest first
     */
    List<WarningResponse> pendingWarnings(String owner) throws ApiException;

    /**
     * Records the player's confirmation of a warning. Idempotent; the backend answers 404 for a warning that
     * belongs to another owner.
     *
     * @param owner the hashed player the warning belongs to
     */
    void acknowledgeWarning(String id, String owner) throws ApiException;
}
