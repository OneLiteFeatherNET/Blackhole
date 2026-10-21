package net.onelitefeather.blackhole.backend.support;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-rolled stand-ins for the Micronaut Data repositories, which only exist as generated beans.
 */
public final class Stubs {

    /**
     * A repository whose {@code findById} answers from {@code byId}, whose {@code save} and
     * {@code update} return their argument, and whose every other method returns an empty or zero
     * value.
     */
    @SuppressWarnings("unchecked")
    public static <T> T repository(Class<T> type, Map<?, ?> byId) {
        return (T) Proxy.newProxyInstance(
                Stubs.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(byId.get(args[0]));
                    case "save", "update" -> args[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private Stubs() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
