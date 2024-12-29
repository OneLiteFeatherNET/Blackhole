package net.onelitefeather.blackhole.request.template;

import net.onelitefeather.blackhole.api.template.PunishTemplate;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public sealed interface TemplateWebRequests permits PunishTemplateRequests {

    String TEMPLATE_BASE_URL = "/template";

    /**
     * Add a new template to the server.
     *
     * @param template the template to add
     * @return the added template
     */
    default @NotNull PunishTemplate add(@NotNull PunishTemplate template) {
        return addAsync(template).join();
    }

    /**
     * Add a new template to the server.
     *
     * @param template the template to add
     * @return the added template
     */
    @NotNull CompletableFuture<PunishTemplate> addAsync(@NotNull PunishTemplate template);

    /**
     * Update a template on the server.
     *
     * @param template the template to update
     * @return the updated template
     */
    default @NotNull PunishTemplate update(@NotNull PunishTemplate template) {
        return updateAsync(template).join();
    }

    @NotNull CompletableFuture<PunishTemplate> updateAsync(@NotNull PunishTemplate template);

    /**
     * Delete a template from the server.
     *
     * @param identifier the identifier of the template
     * @return the deleted template
     */
    default @NotNull PunishTemplate delete(@NotNull UUID identifier) {
        return deleteAsync(identifier).join();
    }

    @NotNull CompletableFuture<PunishTemplate> deleteAsync(@NotNull UUID identifier);

    /**
     * Get all templates from the server.
     *
     * @return the list of templates
     */
    default @NotNull List<PunishTemplate> getAll() {
        return getAllAsync().join();
    }

    @NotNull CompletableFuture<List<PunishTemplate>> getAllAsync();

    /**
     * Get a template by its identifier.
     *
     * @param identifier the identifier of the template
     * @return the template
     */
    default @NotNull Optional<PunishTemplate> get(@NotNull UUID identifier) {
        return getAsync(identifier).join();
    }

    @NotNull CompletableFuture<Optional<PunishTemplate>> getAsync(@NotNull UUID identifier);

}
