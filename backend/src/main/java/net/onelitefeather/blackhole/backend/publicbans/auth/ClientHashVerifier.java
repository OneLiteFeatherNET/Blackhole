package net.onelitefeather.blackhole.backend.publicbans.auth;

/** Checks a presented password against one client's stored hash. Implementations never log or echo the password. */
@FunctionalInterface
public interface ClientHashVerifier {

    boolean matches(String password);
}
