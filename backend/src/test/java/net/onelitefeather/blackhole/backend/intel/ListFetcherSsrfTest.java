package net.onelitefeather.blackhole.backend.intel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No test here touches the network: the resolver and the transport are lambdas that record what they were asked. */
class ListFetcherSsrfTest {

    private static final String PUBLIC_ADDRESS = "93.184.216.34";

    /** A literal only: no name is resolved, so this helper cannot touch DNS. */
    private static InetAddress address(String ip) {
        return IpLiteral.parse(ip).orElseThrow();
    }

    /** A transport that must never be reached by a refused URL, and counts how often it is. */
    private static final class CountingTransport implements ListTransport {
        private final AtomicInteger calls = new AtomicInteger();
        private final ListTransport delegate;

        CountingTransport(ListTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public ListTransport.Reply get(URI uri) throws java.io.IOException {
            this.calls.incrementAndGet();
            return this.delegate.get(uri);
        }
    }

    private static ListTransport.Reply ok(String body) {
        return new ListTransport.Reply(200, null, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aPlainHttpSourceIsRefusedBeforeAnyLookupOrRequest() {
        CountingTransport transport = new CountingTransport(uri -> ok("1.2.3.0/24"));
        ListFetcher fetcher = new ListFetcher(host -> {
            throw new AssertionError("no DNS lookup for a refused scheme");
        }, transport);

        ListRefreshException refused = assertThrows(ListRefreshException.class,
                () -> fetcher.fetch("http://lists.example.test/vpn/ipv4.txt"));

        assertTrue(refused.getMessage().contains("https"), "the refusal names the rule: " + refused.getMessage());
        assertEquals(0, transport.calls.get(), "a refused URL is never requested");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "::1", "169.254.169.254", "10.0.0.5", "172.16.4.4", "192.168.1.1",
            "fc00::1", "fd00:ec2::254", "100.100.100.200", "0.0.0.0", "224.0.0.1"
    })
    void aHostResolvingToANonPublicAddressIsRefused(String ip) throws Exception {
        CountingTransport transport = new CountingTransport(uri -> ok("1.2.3.0/24"));
        ListFetcher fetcher = new ListFetcher(host -> List.of(address(ip)), transport);

        assertThrows(ListRefreshException.class, () -> fetcher.fetch("https://lists.example.test/vpn/ipv4.txt"),
                "a host resolving to " + ip + " must be refused");
        assertEquals(0, transport.calls.get(), "a refused address is never connected to: " + ip);
    }

    @Test
    void aRedirectToAnotherHostIsNotFollowed() throws Exception {
        CountingTransport transport = new CountingTransport(uri ->
                new ListTransport.Reply(302, "https://elsewhere.example.test/list.txt", InputStream.nullInputStream()));
        ListFetcher fetcher = new ListFetcher(host -> List.of(address(PUBLIC_ADDRESS)), transport);

        assertThrows(ListRefreshException.class, () -> fetcher.fetch("https://lists.example.test/vpn/ipv4.txt"),
                "a redirect to a different host fails the refresh");
        assertEquals(1, transport.calls.get(), "the redirect target is not requested");
    }

    @Test
    void aSameHostRedirectOverHttpsIsFollowed() throws Exception {
        CountingTransport transport = new CountingTransport(uri -> {
            if (uri.getPath().endsWith("/moved.txt")) {
                return ok("198.51.100.0/24\n");
            }
            return new ListTransport.Reply(301, "https://lists.example.test/moved.txt", InputStream.nullInputStream());
        });
        ListFetcher fetcher = new ListFetcher(host -> List.of(address(PUBLIC_ADDRESS)), transport);

        byte[] body = fetcher.fetch("https://lists.example.test/vpn/ipv4.txt");

        assertArrayEquals("198.51.100.0/24\n".getBytes(StandardCharsets.UTF_8), body, "a same-host redirect is followed");
        assertEquals(2, transport.calls.get(), "one redirect hop, then the body");
    }

    @Test
    void aBodyOverTheCapIsRefused() {
        InputStream endless = new ZeroBytes(ListFetcher.MAX_BYTES + 1);

        assertThrows(ListRefreshException.class, () -> ListFetcher.readBounded(endless, ListFetcher.MAX_BYTES),
                "a body one byte over the 20 MB cap is refused");
    }

    @Test
    void aBodyAtTheCapIsAccepted() {
        byte[] body = new byte[10];

        assertArrayEquals(body, ListFetcher.readBounded(new ByteArrayInputStream(body), 10),
                "a body exactly at the cap is read in full");
    }

    @Test
    void aPublicHttpsAddressIsFetched() throws Exception {
        ListFetcher fetcher = new ListFetcher(host -> List.of(address(PUBLIC_ADDRESS)),
                uri -> ok("198.51.100.0/24\n"));

        byte[] body = fetcher.fetch("https://lists.example.test/vpn/ipv4.txt");

        assertEquals("198.51.100.0/24\n", new String(body, StandardCharsets.UTF_8), "the body of a public source is returned");
    }

    /** A stream of zero bytes with a fixed length, so the cap test allocates nothing close to 20 MB. */
    private static final class ZeroBytes extends InputStream {
        private long remaining;

        ZeroBytes(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (this.remaining == 0) {
                return -1;
            }
            this.remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (this.remaining == 0) {
                return -1;
            }
            int n = (int) Math.min(length, this.remaining);
            java.util.Arrays.fill(buffer, offset, offset + n, (byte) 0);
            this.remaining -= n;
            return n;
        }
    }
}
