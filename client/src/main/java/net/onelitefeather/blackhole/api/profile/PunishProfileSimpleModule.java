package net.onelitefeather.blackhole.api.profile;

import com.fasterxml.jackson.databind.module.SimpleModule;

public final class PunishProfileSimpleModule {

    public static final SimpleModule INSTANCE = createModule();

    private PunishProfileSimpleModule() {
        throw new UnsupportedOperationException();
    }

    private static SimpleModule createModule() {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(PunishProfile.class, PunishProfileDTO.class);
        return module;
    }
}
