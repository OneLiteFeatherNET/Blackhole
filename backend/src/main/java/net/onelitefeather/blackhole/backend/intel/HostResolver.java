package net.onelitefeather.blackhole.backend.intel;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Resolves a host name of a list URL. A seam, so the address checks are tested without DNS. */
public interface HostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
