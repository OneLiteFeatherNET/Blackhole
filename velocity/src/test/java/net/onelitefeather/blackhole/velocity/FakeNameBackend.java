package net.onelitefeather.blackhole.velocity;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.NameHistoryOwner;
import net.onelitefeather.blackhole.client.model.NameHistoryPage;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A backend for the name history command: what the live lookup, the old-name route and the names route
 * answer is set per test, what was asked is recorded. Any other call fails loudly.
 */
public final class FakeNameBackend {

    /** What the live name lookup answers; empty when no account has the name. */
    public Optional<TargetResolver.Known> live = Optional.empty();
    public ApiException liveFailure;
    /** The names the live lookup was asked for. */
    public final List<String> liveLookups = new ArrayList<>();

    /** What the old-name route answers, and the names it was asked for. */
    public List<NameHistoryOwner> owners = new ArrayList<>();
    public ApiException ownersFailure;
    public final List<String> ownerQueries = new ArrayList<>();

    /** What the names route answers, and the owner and size of each call. */
    public NameHistoryPage page = new NameHistoryPage();
    public ApiException pageFailure;
    public final List<String> pageOwners = new ArrayList<>();
    public final List<Integer> pageSizes = new ArrayList<>();

    public BackendGateway gateway() {
        return (BackendGateway) Proxy.newProxyInstance(
                FakeNameBackend.class.getClassLoader(), new Class<?>[]{BackendGateway.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "resolvePlayer" -> {
                        this.liveLookups.add((String) args[0]);
                        if (this.liveFailure != null) {
                            throw this.liveFailure;
                        }
                        yield this.live;
                    }
                    case "nameOwners" -> {
                        this.ownerQueries.add((String) args[0]);
                        if (this.ownersFailure != null) {
                            throw this.ownersFailure;
                        }
                        yield this.owners;
                    }
                    case "nameHistory" -> {
                        this.pageOwners.add((String) args[0]);
                        this.pageSizes.add((Integer) args[1]);
                        if (this.pageFailure != null) {
                            throw this.pageFailure;
                        }
                        yield this.page;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeNameBackend";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
