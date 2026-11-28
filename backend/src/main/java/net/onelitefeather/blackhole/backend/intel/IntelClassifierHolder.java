package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;

/** Holds the classifier of this replica. The reloader swaps it as a whole, so a reader never sees a half-loaded one. */
@Singleton
public class IntelClassifierHolder {

    private volatile IntelClassifier current = IntelClassifier.empty();

    public IntelClassifier get() {
        return this.current;
    }

    void replace(IntelClassifier classifier) {
        this.current = classifier;
    }
}
