package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A {@link ProtocolSource} over lists. It has no way to reach Discord or the network, like the real one. */
final class FakeProtocolSource implements ProtocolSource {

    final List<Appeal> appeals = new ArrayList<>();
    final List<CaseReader.Entry> entries = new ArrayList<>();

    @Override
    public Optional<Appeal> appeal(UUID appealId) {
        return this.appeals.stream().filter(appeal -> appeal.identifier().equals(appealId)).findFirst();
    }

    @Override
    public List<Appeal> appealsOf(String punishmentIdentifier) {
        return List.copyOf(this.appeals);
    }

    @Override
    public List<CaseReader.Entry> entries(String punishmentIdentifier) {
        return List.copyOf(this.entries);
    }
}
