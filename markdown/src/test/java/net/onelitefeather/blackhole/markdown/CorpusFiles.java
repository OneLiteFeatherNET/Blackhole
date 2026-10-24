package net.onelitefeather.blackhole.markdown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Reads a corpus file from the test resources. */
final class CorpusFiles {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private CorpusFiles() {
    }

    static JsonNode read(String name) {
        try (InputStream in = CorpusFiles.class.getResourceAsStream("/corpus/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing corpus file " + name);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
