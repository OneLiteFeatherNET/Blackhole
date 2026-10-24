package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.support.BanIdTestBed;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The conversation text is Markdown; this turns the stored source into the sanitised block tree the readers get. */
class MessageContentTest {

    private static final String OWNER = "ab".repeat(64);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(List<Map<String, Object>> blocks) {
        return ((List<Map<String, Object>>) blocks.getFirst().get("children")).getFirst();
    }

    @Test
    void aNullOrBlankTextHasNoBlocks() {
        MessageContent content = new MessageContent("onelitefeather.net");

        assertEquals(List.of(), content.blocks(null), "no text, no blocks");
        assertEquals(List.of(), content.blocks("  "), "a blank text, no blocks");
    }

    @Test
    void formattingBecomesNodesOfTheTree() {
        MessageContent content = new MessageContent("onelitefeather.net");

        List<Map<String, Object>> blocks = content.blocks("**bold**");

        assertEquals("paragraph", blocks.getFirst().get("type"), "a paragraph");
        assertEquals("strong", first(blocks).get("type"), "with a strong node");
    }

    @Test
    void theConfiguredDomainDecidesWhichLinksAreExternal() {
        MessageContent content = new MessageContent("onelitefeather.net");

        Map<String, Object> trusted = first(content.blocks("[a](https://appeal.onelitefeather.net/)"));
        Map<String, Object> lookAlike = first(content.blocks("[a](https://evilonelitefeather.net/)"));

        assertEquals(false, trusted.get("external"), "a subdomain of the trusted domain is not external");
        assertEquals(true, lookAlike.get("external"), "a look-alike is external");
    }

    @Test
    void severalDomainsAreSeparatedByCommasAndSpaces() {
        MessageContent content = new MessageContent(" onelitefeather.net , example.org ,, ");

        assertEquals(false, first(content.blocks("[a](https://www.example.org/)")).get("external"), "the second domain is trusted too");
    }

    @Test
    void anEmptyListMakesEveryLinkExternal() {
        MessageContent content = new MessageContent("");

        assertEquals(true, first(content.blocks("[a](https://onelitefeather.net/)")).get("external"), "nothing is trusted");
    }

    @Test
    void aLinkWithAScriptSchemeIsNeverALink() {
        MessageContent content = new MessageContent("onelitefeather.net");

        Map<String, Object> node = first(content.blocks("[x](javascript:alert(1))"));

        assertEquals("text", node.get("type"), "the source stays text");
        assertEquals("[x](javascript:alert(1))", node.get("text"), "exactly as written");
    }

    @Test
    void theTreeIsPlainDataThatSerialisesWithoutTypeInformation() {
        MessageContent content = new MessageContent("onelitefeather.net");

        List<Map<String, Object>> blocks = content.blocks("- a\n- b");

        assertNotNull(blocks.getFirst().get("children"), "lists keep their items under children");
        assertEquals("list", blocks.getFirst().get("type"));
        assertEquals(false, blocks.getFirst().get("ordered"));
        assertEquals(1, blocks.getFirst().get("start"));
    }

    @Test
    void theMessageRulesLookAtLinkTargetsAndCodeToo() {
        InMemoryRepository<PunishmentRepository, PunishmentEntity, String> punishments =
                InMemoryRepository.<PunishmentRepository, PunishmentEntity, String>of(PunishmentRepository.class, PunishmentEntity::getIdentifier).build();
        punishments.given(new PunishmentEntity("p-1", UUID.randomUUID(), OWNER, "network", false, 1L, null, new HashMap<>()), "p-1");
        BanIdTestBed banIds = new BanIdTestBed(punishments.repository());
        String banId = banIds.issueFor("p-1");

        assertNull(MessageRules.check("see [docs](https://example.org/a)", banIds.service), "a normal link is fine");
        assertNotNull(MessageRules.check("[x](https://example.org/a/" + UUID.randomUUID() + ")", banIds.service), "a UUID in a link target is refused");
        assertNotNull(MessageRules.check("[x](https://example.org/?ip=203.0.113.7)", banIds.service), "an IP address in a link target is refused");
        assertNotNull(MessageRules.check("[x](https://example.org/appeal#" + banId + ")", banIds.service), "a Ban ID in a link target is refused");
        assertNotNull(MessageRules.check("```\n" + UUID.randomUUID() + "\n```", banIds.service), "a UUID in a code block is refused");
        assertNotNull(MessageRules.check("`203.0.113.7`", banIds.service), "an IP address in inline code is refused");
    }
}
