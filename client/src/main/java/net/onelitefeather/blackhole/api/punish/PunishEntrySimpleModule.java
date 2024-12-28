package net.onelitefeather.blackhole.api.punish;

import com.fasterxml.jackson.databind.module.SimpleModule;

public final class PunishEntrySimpleModule {

    public final static SimpleModule INSTANCE = createModule();

    private PunishEntrySimpleModule() {
        throw new UnsupportedOperationException();
    }

    private static SimpleModule createModule() {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(PunishEntry.class, PunishEntryDTO.class);
        return module;
    }
}
