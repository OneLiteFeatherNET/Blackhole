package net.onelitefeather.blackhole.backend.publicapi.dto;

import io.micronaut.core.annotation.Nullable;

/** Implemented by the error variant of every public DTO; the controller sends it as {@link PublicErrorBody}. */
public interface PublicError {

    /** A short stable code for the portal; never prose, never an echo of the input. */
    String code();

    /** When the player may file, for {@code NOT_ELIGIBLE}; an ISO instant. */
    @Nullable String availableFrom();

    PublicErrorKind kind();
}
