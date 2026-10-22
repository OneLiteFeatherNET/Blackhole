package net.onelitefeather.blackhole.backend.evidence.scan;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scans through a ClamAV {@code clamd} with the {@code INSTREAM} command over TCP. Blackhole only
 * speaks the daemon's socket protocol (the daemon is GPL-2.0 and is not linked or bundled).
 * Anything but a definite "OK" or "FOUND" - connection refused, timeout, a size-limit error - is
 * {@link EvidenceScanner.Unavailable}, so uploads fail closed.
 *
 * <p>clamd refuses streams over its {@code StreamMaxLength} (25 MB by default); raise it above
 * {@code blackhole.evidence.files.max-video-bytes} in the clamd configuration.
 */
@Singleton
@Requires(property = "blackhole.evidence.scan.enabled", value = "true", defaultValue = "true")
public class ClamdScanner implements EvidenceScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClamdScanner.class);
    private static final int CHUNK = 64 * 1024;
    private static final int MAX_RESPONSE = 4096;

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamdScanner(EvidenceConfig config) {
        EvidenceConfig.Scan.Clamd clamd = config.getScan().getClamd();
        this.host = clamd.getHost();
        this.port = clamd.getPort();
        this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, clamd.getTimeout().toMillis());
    }

    @Override
    public ScanResult scan(Path file) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(this.host, this.port), this.timeoutMillis);
            socket.setSoTimeout(this.timeoutMillis);
            return stream(socket, file);
        } catch (IOException e) {
            LOGGER.warn("Virus scan unavailable ({}:{}): {}", this.host, this.port, e.toString());
            return new Unavailable("cannot reach the virus scanner: " + e.getMessage());
        }
    }

    private ScanResult stream(Socket socket, Path file) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.writeInt(read);
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            // clamd may answer (e.g. "size limit exceeded") and hang up while we are still sending.
            return parse(readResponse(socket), e);
        }
        out.writeInt(0);
        out.flush();
        return parse(readResponse(socket), null);
    }

    private static String readResponse(Socket socket) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        int value;
        while ((value = in.read()) >= 0 && value != 0 && response.size() < MAX_RESPONSE) {
            response.write(value);
        }
        return response.toString(StandardCharsets.UTF_8).strip();
    }

    private static ScanResult parse(String response, IOException writeFailure) {
        if (response.endsWith("FOUND")) {
            String signature = response.replaceFirst("^\\S+:\\s*", "").replaceFirst("\\s*FOUND$", "");
            return new Infected(signature);
        }
        if (response.endsWith("OK") && writeFailure == null) {
            return new Clean();
        }
        String reason = response.isEmpty() && writeFailure != null ? writeFailure.getMessage() : response;
        return new Unavailable("the virus scanner gave no verdict: " + reason);
    }
}
