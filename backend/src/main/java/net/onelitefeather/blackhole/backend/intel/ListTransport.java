package net.onelitefeather.blackhole.backend.intel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * One HTTP GET that does not follow redirects, so {@link ListFetcher} decides about each hop. A seam for tests: the
 * production implementation is {@link JdkListTransport}.
 */
public interface ListTransport {

    /**
     * @param status   the HTTP status code
     * @param location the Location header of a redirect, or null
     * @param body     the response body; the caller closes it
     */
    record Reply(int status, String location, InputStream body) {
    }

    Reply get(URI uri) throws IOException;
}
