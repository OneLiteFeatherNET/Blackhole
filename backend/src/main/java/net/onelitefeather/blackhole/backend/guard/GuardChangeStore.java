package net.onelitefeather.blackhole.backend.guard;

/** Appends to the level history. */
public interface GuardChangeStore {

    void append(GuardChange change);
}
