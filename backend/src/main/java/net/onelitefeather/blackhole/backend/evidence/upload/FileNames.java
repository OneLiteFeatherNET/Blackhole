package net.onelitefeather.blackhole.backend.evidence.upload;

/** Turns a client-supplied file name into a harmless display name. It never becomes part of a storage key. */
public final class FileNames {

    private static final int MAX_LENGTH = 100;

    /**
     * Keeps only the last path segment and replaces everything but letters, digits, dots, dashes,
     * underscores and spaces; leading dots are dropped. A name that ends up empty becomes
     * {@code evidence.<extension>}.
     */
    public static String sanitize(String clientName, String fallbackExtension) {
        String name = clientName == null ? "" : clientName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        name = name.replaceFirst("^[.\\s]+", "");
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH);
        }
        return name.isBlank() ? "evidence." + fallbackExtension : name;
    }

    private FileNames() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
