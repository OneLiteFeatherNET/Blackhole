package net.onelitefeather.blackhole.api.template;

import com.fasterxml.jackson.databind.module.SimpleModule;

public final class PunishTemplateSimpleModule {

    public static final SimpleModule INSTANCE = createModule();

    private PunishTemplateSimpleModule() {
        throw new UnsupportedOperationException();
    }

    private static SimpleModule createModule() {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(PunishTemplate.class, PunishTemplateDTO.class);
        return module;
    }

}
