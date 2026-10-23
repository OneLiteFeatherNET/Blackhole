package net.onelitefeather.blackhole.velocity.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.blackhole.velocity.TestData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmtTest {

    private static final UUID UUID_ID = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
    /** The identifier as the backend stores it: the UUID as 22 character URL-safe Base64. */
    private static final String ID = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteBuffer.allocate(16).putLong(UUID_ID.getMostSignificantBits()).putLong(UUID_ID.getLeastSignificantBits()).array());
    private static final Instant END = Instant.parse("2026-10-22T18:14:00Z");

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void playerSuggestsTheEloCommandInRichMode() {
        Component player = Fmt.player("TheMeinerDev", Mode.RICH);

        ClickEvent click = player.clickEvent();
        assertNotNull(click, "a player name must be clickable for a player");
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action(), "the click must only suggest, never run");
        assertEquals("/elo TheMeinerDev", ((ClickEvent.Payload.Text) click.payload()).value());
    }

    @Test
    void playerHasAHoverInRichMode() {
        assertNotNull(Fmt.player("TheMeinerDev", Mode.RICH).hoverEvent(), "the name explains its click");
    }

    @Test
    void playerHasNoEventsInPlainMode() {
        Component player = Fmt.player("TheMeinerDev", Mode.PLAIN);

        assertNull(player.clickEvent(), "the console cannot click");
        assertNull(player.hoverEvent(), "the console cannot hover");
        assertEquals("TheMeinerDev", plain(player));
    }

    @Test
    void shortIdCopiesItselfInRichMode() {
        Component id = Fmt.shortId(ID, Mode.RICH);

        assertEquals("#A1B2C3D4", plain(id));
        assertEquals(ClickEvent.Action.COPY_TO_CLIPBOARD, id.clickEvent().action());
        assertEquals("#A1B2C3D4", ((ClickEvent.Payload.Text) id.clickEvent().payload()).value());
        assertEquals(HoverEvent.Action.SHOW_TEXT, id.hoverEvent().action());
    }

    @Test
    void shortIdHasNoEventsInPlainMode() {
        Component id = Fmt.shortId(ID, Mode.PLAIN);

        assertEquals("#A1B2C3D4", plain(id));
        assertNull(id.clickEvent(), "no click for the console");
        assertNull(id.hoverEvent(), "no hover for the console");
    }

    @Test
    void shortIdOfAnUnknownIdentifierIsEmpty() {
        assertEquals("", plain(Fmt.shortId(null, Mode.RICH)), "nothing to copy");
        assertNull(Fmt.shortId("abc", Mode.RICH).clickEvent(), "nothing to copy");
    }

    @Test
    void sectionIsItsDisplayNameWithoutEvents() {
        Component section = Fmt.section(TestData.NETWORK);

        assertEquals("Network", plain(section));
        assertNull(section.clickEvent());
        assertNull(section.hoverEvent());
    }

    @Test
    void aScoreDropShowsAMinusSignInRed() {
        Component delta = Fmt.scoreDelta(1000, 900);

        assertEquals("−100", plain(delta), "the Unicode minus, not a hyphen");
        assertEquals(NamedTextColor.RED, delta.color());
    }

    @Test
    void aScoreRiseShowsAPlusSignInGreen() {
        Component delta = Fmt.scoreDelta(900, 1000);

        assertEquals("+100", plain(delta));
        assertEquals(NamedTextColor.GREEN, delta.color());
    }

    @Test
    void anUnchangedScoreIsZeroWithoutDirectionColour() {
        Component delta = Fmt.scoreDelta(900, 900);

        assertEquals("0", plain(delta));
        assertNull(delta.color(), "no direction means no direction colour");
    }

    @Test
    void timeIsShownInTheGivenZone() {
        assertEquals("2026-10-22 20:14 (Europe/Berlin)", plain(Fmt.time(END, ZoneId.of("Europe/Berlin"))));
    }

    @Test
    void sameInstantReadsDifferentlyInAnotherZone() {
        assertEquals("2026-10-22 18:14 (UTC)", plain(Fmt.time(END, ZoneId.of("UTC"))));
    }

    @Test
    void timeAcrossTheDstChangeFollowsTheZone() {
        Instant winter = Instant.parse("2026-12-01T12:00:00Z");

        assertTrue(plain(Fmt.time(winter, ZoneId.of("Europe/Berlin"))).startsWith("2026-12-01 13:00"),
                "Berlin is UTC+1 in winter");
    }
}
