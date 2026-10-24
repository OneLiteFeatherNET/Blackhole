package net.onelitefeather.blackhole.backend.appealconditions.service;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.appealconditions.AppealConditionsStore;
import net.onelitefeather.blackhole.backend.appealconditions.dto.AppealConditionsDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and saves the appeal conditions. A save only ever appends the next version; the text is trimmed, its line endings normalised
 * and checked (1 to {@value #MAX_LENGTH} characters, no control character but newline). Expected failures are answered as the
 * {@link AppealConditionsDTO.Error} variant.
 */
@Singleton
public class AppealConditionsService {

    /** The longest text a version may have. */
    public static final int MAX_LENGTH = 4000;

    private final AppealConditionsStore store;
    private final Clock clock;

    public AppealConditionsService(AppealConditionsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The current version, or an error when none exists. */
    public AppealConditionsDTO current() {
        return this.store.current()
                .<AppealConditionsDTO>map(AppealConditionsService::toResponse)
                .orElseGet(() -> new AppealConditionsDTO.Error("no appeal conditions exist", ErrorKind.NOT_FOUND));
    }

    /** Every version, newest first. */
    public List<AppealConditionsDTO.Response> versions() {
        return this.store.list().stream().map(AppealConditionsService::toResponse).toList();
    }

    /**
     * Saves the text as the next version.
     *
     * @param author the verified staff member
     * @return the new version, or an error: validation for a bad text, conflict when another save took the number
     */
    public AppealConditionsDTO save(@Nullable AppealConditionsDTO.CreateRequest request, UUID author) {
        String text = normalise(request == null ? null : request.text());
        String problem = problem(text);
        if (problem != null) {
            return new AppealConditionsDTO.Error(problem, ErrorKind.VALIDATION);
        }
        Optional<AppealConditionsStore.Version> saved = this.store.append(text, author, this.clock.millis());
        if (saved.isEmpty()) {
            return new AppealConditionsDTO.Error("the conditions changed while saving, review the current version and save again", ErrorKind.CONFLICT);
        }
        return toResponse(saved.get());
    }

    private static String normalise(@Nullable String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String problem(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "the text must not be empty";
        }
        if (text.length() > MAX_LENGTH) {
            return "the text must have at most " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && (c < 0x20 || c == 0x7f)) {
                return "the text must not contain control characters";
            }
        }
        return null;
    }

    private static AppealConditionsDTO.Response toResponse(AppealConditionsStore.Version version) {
        return new AppealConditionsDTO.Response(version.version(), version.text(), Instant.ofEpochMilli(version.createdAt()).toString());
    }
}
