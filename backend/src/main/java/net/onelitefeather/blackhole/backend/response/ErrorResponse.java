package net.onelitefeather.blackhole.backend.response;

/**
 * Marker for a DTO's error variant, so any resource's expected-failure response can be handled
 * generically instead of every resource nesting its own unrelated error shape (see
 * {@code net.onelitefeather.blackhole.backend.dto.PlayerResolveDTO.Error} for the first user).
 */
public interface ErrorResponse {

    String errorMessage();
}
