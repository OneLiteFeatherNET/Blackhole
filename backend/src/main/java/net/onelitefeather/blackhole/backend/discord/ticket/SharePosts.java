package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemRepository;
import net.onelitefeather.blackhole.backend.evidence.share.EvidenceShareStore;
import net.onelitefeather.blackhole.backend.evidence.share.ExcerptRenderer;

import java.util.List;
import java.util.UUID;

/**
 * What a Discord post of an evidence share carries: the server-rendered lines of an excerpt, or the file of an image share. The bot
 * posts exactly this, so the lines are rendered once, here, with the same rules as the portal. A share whose evidence is gone has no
 * text and no file; the bot then posts that it is unavailable.
 */
@Singleton
public class SharePosts {

    /**
     * @param text  the excerpt lines ("speaker: text", one per line), {@code null} when there are none
     * @param files the image of an image share, as one file under the share id; empty otherwise
     */
    public record Post(@Nullable String text, List<DiscordTicketDTO.OutboxFile> files) {

        static final Post UNAVAILABLE = new Post(null, List.of());
    }

    private final EvidenceShareStore shares;
    private final EvidenceItemRepository items;

    public SharePosts(EvidenceShareStore shares, EvidenceItemRepository items) {
        this.shares = shares;
        this.items = items;
    }

    /** The post of a share of the punishment; {@link Post#UNAVAILABLE} when the share or its evidence is gone. */
    public Post describe(String punishmentIdentifier, UUID shareId) {
        EvidenceShareStore.Share share = this.shares.find(shareId, punishmentIdentifier).orElse(null);
        if (share == null) {
            return Post.UNAVAILABLE;
        }
        EvidenceItemEntity item = this.items.findById(share.evidenceId()).orElse(null);
        if (item == null) {
            return Post.UNAVAILABLE;
        }
        if ("EXCERPT".equals(share.mode())) {
            String appellant = share.appellantSpeaker() == null ? ExcerptRenderer.APPELLANT_LABEL : share.appellantSpeaker();
            List<ExcerptRenderer.Line> lines = ExcerptRenderer.render(ExcerptRenderer.linesOf(item.getPayload()), share.selection(), appellant);
            if (lines.isEmpty()) {
                return Post.UNAVAILABLE;
            }
            return new Post(lines.stream().map(line -> line.speaker() + ": " + line.text()).reduce((a, b) -> a + "\n" + b).orElse(""), List.of());
        }
        if (item.getFileKey() == null) {
            return Post.UNAVAILABLE;
        }
        return new Post(null, List.of(new DiscordTicketDTO.OutboxFile(shareId.toString(), item.getFileName(), item.getFileContentType(),
                item.getFileSize())));
    }
}
