package net.onelitefeather.blackhole.backend.evidence.scan;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A protocol-level stand-in for clamd on a loopback port: it understands {@code zINSTREAM}, records
 * the command and the streamed bytes, and answers with a scripted reply. Serves exactly one
 * connection, so every test uses its own instance.
 */
public final class StubClamd implements AutoCloseable {

    private final ServerSocket server;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private final ByteArrayOutputStream streamed = new ByteArrayOutputStream();
    private volatile String command;

    /**
     * @param reply what the daemon answers after the stream's terminator, without the trailing NUL
     */
    public StubClamd(String reply) throws IOException {
        this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> serve(reply), "stub-clamd");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(String reply) {
        try (Socket socket = this.server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream command = new ByteArrayOutputStream();
            int value;
            while ((value = in.read()) > 0) {
                command.write(value);
            }
            this.command = command.toString(StandardCharsets.US_ASCII);
            int length;
            while ((length = in.readInt()) > 0) {
                this.streamed.write(in.readNBytes(length));
            }
            OutputStream out = socket.getOutputStream();
            out.write(reply.getBytes(StandardCharsets.UTF_8));
            out.write(0);
            out.flush();
            this.finished.complete(null);
        } catch (IOException e) {
            this.finished.completeExceptionally(e);
        }
    }

    public int port() {
        return this.server.getLocalPort();
    }

    /** Waits until the connection has been served; completing is what makes the recorded values safe to read. */
    public void awaitServed() throws InterruptedException, ExecutionException, TimeoutException {
        this.finished.get(10, TimeUnit.SECONDS);
    }

    public String command() {
        return this.command;
    }

    public byte[] streamed() {
        return this.streamed.toByteArray();
    }

    @Override
    public void close() throws IOException {
        this.server.close();
    }
}
