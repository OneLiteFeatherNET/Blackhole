package net.onelitefeather.blackhole.request.template;

import net.onelitefeather.blackhole.api.template.PunishTemplate;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public sealed interface TemplateWebRequests permits PunishTemplateRequests {

    String TEMPLATE_BASE_URL = "/template";

    /**
     * Add a new template to the server.
     *
     * @param template the template to add
     * @return the added template
     */
    @NotNull PunishTemplate add(@NotNull PunishTemplate template);

    /**
     * Update a template on the server.
     *
     * @param template the template to update
     * @return the updated template
     */
    @NotNull PunishTemplate update(@NotNull PunishTemplate template);

    /**
     * Delete a template from the server.
     *
     * @param identifier the identifier of the template
     * @return the deleted template
     */
    @NotNull PunishTemplate delete(@NotNull UUID identifier);

    /**
     * Get all templates from the server.
     *
     * @return the list of templates
     */
    @NotNull List<PunishTemplate> getAll();

}
