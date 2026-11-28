package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** The system resolver, used only for the configured list hosts. */
@Singleton
public class SystemHostResolver implements HostResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
