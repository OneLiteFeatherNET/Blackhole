package net.onelitefeather.blackhole.backend.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Checks an {@link IntegrationSpec} against the registration rules; returns the problems, empty when valid. */
public final class IntegrationValidator {

    static final Pattern SOURCE_SYSTEM = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    static final Pattern CLIENT_ID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final Pattern KIND_WITH_VERSION = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}:[1-9][0-9]{0,2}$");
    static final Pattern TASK = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$");
    static final Pattern STATIC_SERVER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    static final int DISPLAY_NAME_MAX = 128;

    private IntegrationValidator() {
    }

    public static List<String> validate(IntegrationSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec.sourceSystem() == null || !SOURCE_SYSTEM.matcher(spec.sourceSystem()).matches()) {
            errors.add("sourceSystem: lower case letters, digits and hyphens, starting with a letter, at most 64 characters");
        }
        if (spec.entraClientId() == null || !CLIENT_ID.matcher(spec.entraClientId()).matches()) {
            errors.add("entraClientId: must be the client id (GUID) of the Entra app registration");
        }
        if (spec.displayName() == null || spec.displayName().isBlank() || spec.displayName().length() > DISPLAY_NAME_MAX) {
            errors.add("displayName: required, at most " + DISPLAY_NAME_MAX + " characters");
        }
        if (spec.allowedKinds().isEmpty()) {
            errors.add("allowedKinds: at least one kind with its version, for example DETECTION:1");
        }
        for (String kind : spec.allowedKinds()) {
            if (!KIND_WITH_VERSION.matcher(kind).matches()) {
                errors.add("allowedKinds: '" + kind + "' is not KIND:version");
            }
        }
        for (String task : spec.allowedTasks()) {
            if (!TASK.matcher(task).matches()) {
                errors.add("allowedTasks: '" + task + "' is not a CloudNet task name");
            }
        }
        for (String server : spec.allowedStaticServers()) {
            if (!STATIC_SERVER.matcher(server).matches()) {
                errors.add("allowedStaticServers: '" + server + "' is not a server name");
            }
        }
        if (spec.allowedTasks().isEmpty() && spec.allowedStaticServers().isEmpty()) {
            errors.add("scope: list at least one task (allowedTasks) or one static server (allowedStaticServers)");
        }
        return errors;
    }
}
