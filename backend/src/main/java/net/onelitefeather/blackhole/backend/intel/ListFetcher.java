package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Downloads one list source with the hardening of openspec add-vpn-proxy-detection: HTTPS only; no address that is
 * loopback, link-local, private, unique-local, multicast, unspecified, carrier-grade NAT or a cloud metadata address;
 * redirects followed only to the same host, at most three; a body of at most 20 MB.
 * The URLs come from configuration, but the checks run anyway: a configured host can still resolve somewhere it should not.
 */
@Singleton
public class ListFetcher {

    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int MAX_REDIRECTS = 3;

    private final HostResolver resolver;
    private final ListTransport transport;

    public ListFetcher(HostResolver resolver, ListTransport transport) {
        this.resolver = resolver;
        this.transport = transport;
    }

    /**
     * @return the body of the list
     * @throws ListRefreshException when the URL or any address is refused, the server answers an error, or the body is too large
     */
    public byte[] fetch(String url) {
        URI uri = parse(url);
        for (int hop = 0; ; hop++) {
            checkUrl(uri);
            checkResolvedAddresses(uri.getHost());
            ListTransport.Reply reply = request(uri);
            if (reply.status() >= 300 && reply.status() < 400) {
                closeQuietly(reply.body());
                URI next = redirectTarget(uri, reply.location());
                if (hop + 1 > MAX_REDIRECTS) {
                    throw new ListRefreshException("too many redirects");
                }
                uri = next;
                continue;
            }
            if (reply.status() != 200) {
                closeQuietly(reply.body());
                throw new ListRefreshException("the source answered HTTP " + reply.status());
            }
            try (InputStream body = reply.body()) {
                return readBounded(body, MAX_BYTES);
            } catch (IOException e) {
                throw new ListRefreshException("the download failed", e);
            }
        }
    }

    /**
     * @return the bytes of the stream, at most {@code max} of them
     * @throws ListRefreshException when the stream holds more than {@code max} bytes, or cannot be read
     */
    static byte[] readBounded(InputStream stream, long max) {
        try {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new ListRefreshException("the list is larger than the 20 MB limit");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ListRefreshException("the download failed", e);
        }
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new ListRefreshException("the source has no url");
        }
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ListRefreshException("the source url is not a valid URI", e);
        }
    }

    static void checkUrl(URI uri) {
        if (uri.getScheme() == null || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new ListRefreshException("list sources must use https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ListRefreshException("the source url has no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ListRefreshException("the source url must not contain user info");
        }
    }

    /** A redirect is followed only to the same host, and only over HTTPS. */
    static URI redirectTarget(URI from, String location) {
        if (location == null || location.isBlank()) {
            throw new ListRefreshException("the redirect has no location");
        }
        URI to = from.resolve(location);
        checkUrl(to);
        if (!from.getHost().equalsIgnoreCase(to.getHost())) {
            throw new ListRefreshException("a redirect to another host is refused");
        }
        return to;
    }

    private void checkResolvedAddresses(String host) {
        List<InetAddress> addresses;
        try {
            addresses = this.resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new ListRefreshException("the source host does not resolve", e);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new ListRefreshException("the source host does not resolve");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new ListRefreshException("the source resolves to a non-public address");
            }
        }
    }

    /** @return false for every address a list source must never be fetched from */
    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return false; // unique-local fc00::/7, which includes the AWS IPv6 metadata address
        }
        if (address instanceof Inet4Address && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64) {
            return false; // carrier-grade NAT 100.64.0.0/10, which includes the Alibaba metadata address
        }
        return true;
    }

    private ListTransport.Reply request(URI uri) {
        try {
            return this.transport.get(uri);
        } catch (IOException e) {
            throw new ListRefreshException("the download failed", e);
        }
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // the reply is discarded either way
        }
    }
}
