package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.StaffLabels;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ProtocolModel} as the two files of a protocol: a human-readable text and JSON with a
 * {@code schemaVersion}. Both are deterministic: field order is fixed, the line feed is {@code \n} on every
 * platform, times are ISO-8601 UTC, and the only value that is not part of the model is the generation
 * time, which the caller passes in from its injected clock.
 */
public final class ProtocolRenderer {

    /**
     * The version of the JSON layout. Version 2: a message entry has {@code format: "markdown"}, because its text is the Markdown source
     * as written (not a rendering), and the layout is otherwise as version 1.
     */
    public static final int SCHEMA_VERSION = 2;

    static final String REMOVED = "[removed for data protection]";

    private static final ObjectWriter JSON = new ObjectMapper().writer(prettyPrinter());

    /** The two files, as UTF-8 bytes. */
    public record Rendered(byte[] text, byte[] json) {
    }

    private ProtocolRenderer() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    /**
     * @param version     the version number this rendering is stored as
     * @param generatedAt the generation time, epoch millis
     */
    public static Rendered render(ProtocolModel model, int version, long generatedAt) {
        return new Rendered(text(model, version, generatedAt).getBytes(StandardCharsets.UTF_8), json(model, version, generatedAt));
    }

    // ---- JSON ----

    private static byte[] json(ProtocolModel model, int version, long generatedAt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("appealId", model.appealId().toString());
        document.put("punishmentIdentifier", model.punishmentIdentifier());
        document.put("version", version);
        document.put("generatedAt", time(generatedAt));
        document.put("decision", model.decision());
        document.put("decidedAt", time(model.decidedAt()));
        List<Map<String, Object>> entries = new ArrayList<>();
        model.entries().forEach(entry -> entries.add(jsonEntry(entry)));
        document.put("entries", entries);
        List<Map<String, Object>> source = new ArrayList<>();
        for (ProtocolModel.SourceEvent event : model.source()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", event.eventId().toString());
            item.put("seq", event.seq());
            item.put("type", event.type());
            item.put("redacted", event.redacted());
            source.add(item);
        }
        document.put("source", source);
        Map<String, Object> completeness = new LinkedHashMap<>();
        completeness.put("entryCount", model.entryCount());
        completeness.put("firstSeq", model.firstSeq());
        completeness.put("lastSeq", model.lastSeq());
        completeness.put("digestAlgorithm", "SHA-256");
        completeness.put("digestInput", "one line per source event: eventId|seq|type|redacted (1 or 0), in seq order, joined by a line feed");
        completeness.put("sourceDigest", model.digest());
        if (model.ticket() != null) {
            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("transcriptComplete", model.ticket().complete());
            ticket.put("messageCount", model.ticket().count());
            ticket.put("lastMessageId", model.ticket().lastMessageId());
            completeness.put("ticket", ticket);
        }
        document.put("completeness", completeness);
        try {
            return (JSON.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write the protocol", e);
        }
    }

    private static Map<String, Object> jsonEntry(ProtocolModel.Entry entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", entry.seq());
        item.put("at", time(entry.at()));
        ProtocolModel.Author author = entry.author();
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("label", author.label());
        who.put("kind", author.kind());
        who.put("trust", author.trust());
        if (author.participantId() != null) {
            who.put("participantId", author.participantId());
        }
        switch (entry) {
            case ProtocolModel.MessageEntry message -> {
                item.put("kind", "MESSAGE");
                item.put("channel", message.channel());
                item.put("author", who);
                item.put("visibility", message.visibility());
                item.put("format", "markdown");
                item.put("text", message.text());
                List<Map<String, Object>> texts = new ArrayList<>();
                for (ProtocolModel.Text text : message.texts()) {
                    Map<String, Object> version = new LinkedHashMap<>();
                    version.put("seq", text.seq());
                    version.put("at", time(text.at()));
                    version.put("text", text.text());
                    texts.add(version);
                }
                item.put("texts", texts);
                item.put("deletedAt", message.deletedAt() == null ? null : time(message.deletedAt()));
                item.put("redacted", message.redacted());
                List<Map<String, Object>> attachments = new ArrayList<>();
                for (ProtocolModel.Attachment attachment : message.attachments()) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("name", attachment.name());
                    file.put("type", attachment.type());
                    file.put("size", attachment.size());
                    if (attachment.chatFile()) {
                        file.put("sha256Prefix", attachment.sha12());
                        file.put("provenance", attachment.provenance());
                        file.put("marker", attachment.marker());
                    }
                    attachments.add(file);
                }
                item.put("attachments", attachments);
                List<Map<String, Object>> embeds = new ArrayList<>();
                for (ProtocolModel.Embed embed : message.embeds()) {
                    Map<String, Object> shown = new LinkedHashMap<>();
                    shown.put("type", embed.type());
                    shown.put("host", embed.host());
                    embeds.add(shown);
                }
                item.put("embeds", embeds);
            }
            case ProtocolModel.EventEntry event -> {
                item.put("kind", "EVENT");
                item.put("type", event.type());
                item.put("author", who);
                item.put("visibility", event.visibility());
                item.put("details", event.details());
                String marker = marker(event);
                if (marker != null) {
                    item.put("marker", marker);
                }
            }
        }
        return item;
    }

    // ---- text ----

    private static String text(ProtocolModel model, int version, long generatedAt) {
        StringBuilder out = new StringBuilder();
        out.append("Appeal protocol\n");
        out.append("Appeal: ").append(model.appealId()).append('\n');
        out.append("Punishment: ").append(model.punishmentIdentifier()).append('\n');
        out.append("Decision: ").append(model.decision()).append(" (").append(time(model.decidedAt())).append(")\n");
        out.append("Version: ").append(version).append('\n');
        out.append("Generated: ").append(time(generatedAt)).append('\n');
        out.append("Times are UTC. Entries are in sequence order. Visibility shows who could read an entry: PLAYER or STAFF only.\n");
        out.append("Message texts are the Markdown source exactly as written, not a rendering.\n");
        out.append(StaffLabels.NOTICE).append('\n');
        for (ProtocolModel.Entry entry : model.entries()) {
            out.append('\n');
            switch (entry) {
                case ProtocolModel.MessageEntry message -> message(out, message);
                case ProtocolModel.EventEntry event -> event(out, event);
            }
        }
        out.append("\nCompleteness check\n");
        out.append("Entries: ").append(model.entryCount()).append('\n');
        out.append("First seq: ").append(model.firstSeq() == null ? "-" : model.firstSeq()).append('\n');
        out.append("Last seq: ").append(model.lastSeq() == null ? "-" : model.lastSeq()).append('\n');
        out.append("Source events: ").append(model.source().size()).append('\n');
        out.append("Source digest (SHA-256): ").append(model.digest()).append('\n');
        if (model.ticket() != null) {
            ProtocolModel.Ticket ticket = model.ticket();
            out.append("Discord ticket transcript: ").append(ticket.complete() ? "complete" : "NOT verified complete");
            if (ticket.complete()) {
                out.append(", ").append(ticket.count() == null ? "?" : ticket.count()).append(" messages, last message id ")
                        .append(ticket.lastMessageId() == null ? "-" : ticket.lastMessageId());
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void message(StringBuilder out, ProtocolModel.MessageEntry message) {
        ProtocolModel.Author author = message.author();
        out.append('#').append(message.seq()).append("  ").append(time(message.at())).append("  ").append(message.channel())
                .append("  ").append(author.label()).append(" (").append(author.kind()).append(", trust ").append(author.trust())
                .append(")  visibility ").append(message.visibility()).append('\n');
        indent(out, message.redacted() ? REMOVED : valueOrEmpty(message.text()), "    ");
        if (message.texts().size() > 1) {
            out.append("    Earlier texts, oldest first:\n");
            for (int i = 0; i < message.texts().size() - 1; i++) {
                ProtocolModel.Text earlier = message.texts().get(i);
                out.append("      #").append(earlier.seq()).append("  ").append(time(earlier.at())).append(i == 0 ? "  (first text)" : "  (edit)").append('\n');
                indent(out, earlier.text() == null ? REMOVED : earlier.text(), "        ");
            }
            ProtocolModel.Text last = message.texts().getLast();
            out.append("    Current text since #").append(last.seq()).append("  ").append(time(last.at())).append('\n');
        }
        if (message.deletedAt() != null) {
            out.append("    Deleted at ").append(time(message.deletedAt())).append("; the last text is kept above.\n");
        }
        for (ProtocolModel.Attachment attachment : message.attachments()) {
            if (attachment.chatFile()) {
                out.append("    ").append(attachment.marker()).append(attachment.provenance() == null ? "" : "  (" + attachment.provenance() + ")").append('\n');
                continue;
            }
            out.append("    Attachment: ").append(attachment.name() == null ? "(unnamed)" : attachment.name())
                    .append(" (").append(attachment.type() == null ? "unknown type" : attachment.type()).append(", ")
                    .append(attachment.size() == null ? "unknown size" : attachment.size() + " bytes").append(")\n");
        }
        for (ProtocolModel.Embed embed : message.embeds()) {
            out.append("    Embed: ").append(embed.type() == null ? "unknown type" : embed.type())
                    .append(embed.host() == null ? "" : " from " + embed.host()).append('\n');
        }
    }

    private static void event(StringBuilder out, ProtocolModel.EventEntry event) {
        ProtocolModel.Author author = event.author();
        out.append('#').append(event.seq()).append("  ").append(time(event.at())).append("  ").append(event.type())
                .append("  ").append(author.label()).append(" (").append(author.kind()).append(", trust ").append(author.trust())
                .append(")  visibility ").append(event.visibility()).append('\n');
        String marker = marker(event);
        if (marker != null) {
            out.append("    ").append(marker).append('\n');
        }
        if (!event.details().isEmpty()) {
            List<String> parts = new ArrayList<>();
            event.details().forEach((key, value) -> parts.add(key + " " + value));
            out.append("    ").append(String.join(", ", parts)).append('\n');
        }
    }

    /** The marker line of an evidence share or revoke: what kind and how many lines, never the lines or the image. */
    private static @Nullable String marker(ProtocolModel.EventEntry event) {
        return switch (event.type()) {
            case "evidence.shared" -> "EXCERPT".equals(event.details().get("mode"))
                    ? "[evidence shared: chat excerpt, " + event.details().get("lineCount") + (Long.valueOf(1).equals(longOf(event.details().get("lineCount"))) ? " line]" : " lines]")
                    : "[evidence shared: screenshot]";
            case "evidence.share_revoked" -> "[evidence share withdrawn]";
            default -> null;
        };
    }

    private static @Nullable Long longOf(@Nullable Object value) {
        return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
    }

    private static void indent(StringBuilder out, String text, String prefix) {
        if (text.isEmpty()) {
            out.append(prefix).append("(empty)\n");
            return;
        }
        text.lines().forEach(line -> out.append(prefix).append(line).append('\n'));
    }

    private static String valueOrEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static String time(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
