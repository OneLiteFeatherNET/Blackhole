package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.markdown.LinkRules;
import net.onelitefeather.blackhole.markdown.MarkdownParser;
import net.onelitefeather.blackhole.markdown.Node;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Turns the stored source of a conversation message into the sanitised block tree its readers draw. The source is stored as
 * written and never changed; the tree is derived on every read, so a parser change changes no row. Links are checked by the
 * {@code markdown} module (http and https only, nothing is fetched); the trusted domains come from
 * {@code blackhole.chat.links.trusted-domains}.
 */
@Singleton
public class MessageContent {

    private final LinkRules rules;

    /**
     * @param trustedDomains comma separated domains whose links are not external; blank entries are ignored and an empty value
     *                       makes every link external
     */
    public MessageContent(@Value("${blackhole.chat.links.trusted-domains:" + LinkRules.DEFAULT_TRUSTED_DOMAIN + "}") String trustedDomains) {
        this.rules = new LinkRules(Arrays.asList(trustedDomains.split(",")));
    }

    /**
     * The blocks of a message.
     *
     * @param source the stored text; {@code null} (a removed message) has no blocks
     * @return the tree as plain data ({@code type}, fields, {@code children}), never {@code null}
     */
    public List<Map<String, Object>> blocks(@Nullable String source) {
        if (source == null) {
            return List.of();
        }
        return Node.toMaps(new MarkdownParser(this.rules).parse(source));
    }
}
