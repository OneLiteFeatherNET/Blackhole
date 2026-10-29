package net.onelitefeather.blackhole.backend.publicbans;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/** Supplies the one {@link PublicBanHasher} of the application, keyed by {@code BLACKHOLE_PUBLIC_BAN_KEY}. */
@Factory
public class PublicBanHasherFactory {

    @Singleton
    PublicBanHasher publicBanHasher(@Value("${blackhole.public-bans.key:}") String key) {
        return new PublicBanHasher(key);
    }
}
