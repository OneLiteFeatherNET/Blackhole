package net.onelitefeather.blackhole.backend.playerresolver;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.dto.PlayerResolveDTO;

import java.util.regex.Pattern;

@Singleton
public class PlayerLookupService {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private final PlayerResolverService resolverService;

    public PlayerLookupService(PlayerResolverService resolverService) {
        this.resolverService = resolverService;
    }

    public PlayerResolveDTO resolve(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            return new PlayerResolveDTO.Error("Not a valid Minecraft player name");
        }
        return this.resolverService.resolve(name)
                .<PlayerResolveDTO>map(PlayerResolveDTO.Response::of)
                .orElseGet(() -> new PlayerResolveDTO.Error("No resolver found a player named " + name));
    }
}
