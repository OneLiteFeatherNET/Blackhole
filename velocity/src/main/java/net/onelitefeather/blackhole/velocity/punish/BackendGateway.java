package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
import net.onelitefeather.blackhole.client.model.EloProfileResponse;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.client.model.ReportCategory;
import net.onelitefeather.blackhole.client.model.ReportDTO;
import net.onelitefeather.blackhole.client.model.ReportFeedbackResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
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
     * @param evidenceCorrelationId the key chat evidence was posted under ahead of the punishment, or null
     */
    PunishmentApplyResponse apply(String owner, String templateKey, UUID source, String evidenceCorrelationId) throws ApiException;

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
}
