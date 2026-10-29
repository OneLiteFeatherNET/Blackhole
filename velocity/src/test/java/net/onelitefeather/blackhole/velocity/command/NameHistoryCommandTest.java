package net.onelitefeather.blackhole.velocity.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.NameHistoryName;
import net.onelitefeather.blackhole.client.model.NameHistoryOwner;
import net.onelitefeather.blackhole.client.model.NameHistoryPage;
import net.onelitefeather.blackhole.velocity.FakeNameBackend;
import net.onelitefeather.blackhole.velocity.RecordingPlayer;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.incendo.cloud.annotations.Permission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /blackhole <user> names}: who is resolved how, and what the staff member is shown. */
class NameHistoryCommandTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");
    private static final UUID ALEX = UUID.fromString("00000000-0000-4000-8000-00000000a1ec");
    private static final UUID SAM = UUID.fromString("00000000-0000-4000-8000-0000000005a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-00000000071e");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long OCT_1 = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli();
    private static final long OCT_20 = Instant.parse("2026-10-20T08:30:00Z").toEpochMilli();

    private final FakeNameBackend backend = new FakeNameBackend();
    private final RecordingPlayer staff = new RecordingPlayer(STAFF, "Staff");
    private final RecordingPlayer onlineAlex = new RecordingPlayer(ALEX, "Alex");

    private TargetResolver.OnlineLookup nobodyOnline() {
        return name -> Optional.empty();
    }

    private NameHistoryCommand command() {
        return new NameHistoryCommand(this.backend.gateway(), name -> name.equalsIgnoreCase("Alex")
                ? Optional.of(this.onlineAlex.player()) : Optional.empty(), UTC);
    }

    private NameHistoryCommand offlineCommand() {
        return new NameHistoryCommand(this.backend.gateway(), nobodyOnline(), UTC);
    }

    private static NameHistoryOwner owner(UUID uuid, String currentName) {
        return new NameHistoryOwner().owner(UUIDConverter.convertToSHA(uuid)).playerUuid(uuid.toString())
                .currentName(currentName).firstSeen(OCT_1).lastSeen(OCT_20);
    }

    private static NameHistoryName name(String name, long firstSeen, long lastSeen) {
        return new NameHistoryName().name(name).firstSeen(firstSeen).lastSeen(lastSeen);
    }

    /** The text of a message with every translation key and its arguments kept visible, for assertions. */
    private static String flat(Component component) {
        StringBuilder out = new StringBuilder();
        if (component instanceof TranslatableComponent translatable) {
            out.append('[').append(translatable.key());
            translatable.arguments().forEach(argument -> out.append(' ').append(flat(argument.asComponent())));
            out.append(']');
        } else if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> out.append(flat(child)));
        return out.toString();
    }

    private List<String> sent() {
        List<String> texts = new ArrayList<>();
        this.staff.messages().forEach(message -> texts.add(flat(message)));
        return texts;
    }

    /** Every line of every message, so each listed name can be asserted on its own. */
    private List<String> lines() {
        return sent().stream().flatMap(message -> Arrays.stream(message.split("\n"))).toList();
    }

    private static long countOf(List<String> texts, String fragment) {
        return texts.stream().filter(text -> text.contains(fragment)).count();
    }

    @Test
    void anOnlinePlayerIsResolvedWithoutALiveLookup() {
        this.backend.page = new NameHistoryPage().owner(UUIDConverter.convertToSHA(ALEX)).names(List.of());

        command().list(this.staff.player(), "Alex");

        assertEquals(List.of(), this.backend.liveLookups, "a player online here needs no live name lookup");
        assertEquals(List.of(UUIDConverter.convertToSHA(ALEX)), this.backend.pageOwners, "the names are read for the online player");
    }

    @Test
    void anOfflineNameIsResolvedThroughTheLiveLookup() {
        this.backend.live = Optional.of(new TargetResolver.Known(ALEX, "Alex"));
        this.backend.page = new NameHistoryPage().owner(UUIDConverter.convertToSHA(ALEX)).names(List.of());

        offlineCommand().list(this.staff.player(), "alex");

        assertEquals(List.of("alex"), this.backend.liveLookups, "the typed name is looked up live");
        assertEquals(List.of(UUIDConverter.convertToSHA(ALEX)), this.backend.pageOwners, "the names of the resolved account are read");
        assertEquals(List.of(), this.backend.ownerQueries, "the old-name route is not needed for a live name");
    }

    @Test
    void aNameTheLiveLookupDoesNotKnowIsResolvedThroughTheOldNameRoute() {
        this.backend.owners = List.of(owner(SAM, "Sam"));
        this.backend.page = new NameHistoryPage().owner(UUIDConverter.convertToSHA(SAM)).names(List.of());

        offlineCommand().list(this.staff.player(), "OldSam");

        assertEquals(List.of("OldSam"), this.backend.ownerQueries, "the old name is looked up in the history");
        assertEquals(List.of(UUIDConverter.convertToSHA(SAM)), this.backend.pageOwners, "the single account the old name belongs to is listed");
    }

    @Test
    void severalAccountsWithOneOldNameAreListedAndNoneIsGuessed() {
        this.backend.owners = List.of(owner(SAM, "Sam"), owner(OTHER, "Other"));

        offlineCommand().list(this.staff.player(), "Dupe");

        assertEquals(List.of(), this.backend.pageOwners, "no account is picked when the old name matches several");
        List<String> texts = lines();
        assertTrue(texts.get(0).contains("[names.ambiguous.header"), texts.toString());
        assertEquals(1, countOf(texts, SAM.toString()), "the first account is listed by its UUID");
        assertEquals(1, countOf(texts, OTHER.toString()), "the second account is listed by its UUID");
    }

    @Test
    void anUnknownNameIsReportedAsNotFound() {
        offlineCommand().list(this.staff.player(), "Nobody");

        assertEquals(List.of(), this.backend.pageOwners, "nothing is read for a name no source knows");
        assertTrue(sent().get(0).contains("[names.not_found"), sent().toString());
    }

    @Test
    void theNamesAreListedNewestFirstWithFirstAndLastSeen() {
        this.backend.page = new NameHistoryPage().owner(UUIDConverter.convertToSHA(ALEX))
                .names(List.of(name("Alex", OCT_20, OCT_20), name("OldAlex", OCT_1, OCT_1)));

        command().list(this.staff.player(), "Alex");

        List<String> texts = lines();
        int newest = indexOfContaining(texts, "Alex");
        int oldest = indexOfContaining(texts, "OldAlex");
        assertTrue(newest > 0 && newest < oldest, "the newest name comes first: " + texts);
        assertTrue(texts.get(oldest).contains("2026-10-01 10:00"), "the first seen time is shown: " + texts.get(oldest));
        assertTrue(texts.get(oldest).contains("2026-10-01 10:00") && texts.get(newest).contains("2026-10-20 08:30"),
                "the last seen time is shown: " + texts);
    }

    @Test
    void theListingAsksForAtMostTwentyNames() {
        List<NameHistoryName> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(name("Name" + i, OCT_1, OCT_20));
        }
        this.backend.page = new NameHistoryPage().owner(UUIDConverter.convertToSHA(ALEX)).names(many);

        command().list(this.staff.player(), "Alex");

        assertEquals(List.of(20), this.backend.pageSizes, "the backend is asked for 20 names");
        assertEquals(20L, countOf(lines(), "[names.entry"), "no more than 20 names are shown");
    }

    @Test
    void theHeaderNamesTheRawUuidOfTheAccount() {
        this.backend.page = new NameHistoryPage().owner(UUIDConverter.convertToSHA(ALEX))
                .names(List.of(name("Alex", OCT_1, OCT_20)));

        command().list(this.staff.player(), "Alex");

        assertTrue(lines().get(0).contains("[names.header") && lines().get(0).contains(ALEX.toString()), lines().toString());
    }

    @Test
    void aBackendFailureOnTheNamesPrintsTheUnavailableMessageAndDoesNotThrow() {
        this.backend.pageFailure = new ApiException(500, "backend down");

        assertDoesNotThrow(() -> command().list(this.staff.player(), "Alex"));

        assertTrue(sent().getLast().contains("[names.unavailable"), sent().toString());
    }

    @Test
    void aBackendFailureOnTheOldNameRoutePrintsTheUnavailableMessageAndDoesNotThrow() {
        this.backend.ownersFailure = new ApiException(500, "backend down");

        assertDoesNotThrow(() -> offlineCommand().list(this.staff.player(), "Old"));

        assertTrue(sent().getLast().contains("[names.unavailable"), sent().toString());
    }

    @Test
    void theNamesCommandNeedsTheInfoPermission() throws NoSuchMethodException {
        Method names = NameHistoryCommand.class.getMethod("names",
                org.incendo.cloud.context.CommandContext.class, String.class);

        String[] permissions = names.getAnnotation(Permission.class).value();

        assertTrue(Arrays.asList(permissions).contains("blackhole.user.info"), Arrays.toString(permissions));
    }

    private static int indexOfContaining(List<String> texts, String fragment) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains("[names.entry " + fragment + " ")) {
                return i;
            }
        }
        return -1;
    }
}
