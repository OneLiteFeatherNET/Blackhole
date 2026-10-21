package net.onelitefeather.blackhole.backend.support;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An in-memory stand-in for a Micronaut Data repository, which only exists as a generated bean.
 * It implements the CRUD methods over a map; derived finder methods are supplied per test through
 * {@link Builder#finder}, so a test states exactly what the service is expected to ask for.
 *
 * @param <R>  the repository interface
 * @param <T>  the entity type
 * @param <ID> the id type
 */
public final class InMemoryRepository<R, T, ID> {

    private final Map<ID, T> store = new LinkedHashMap<>();
    private final R proxy;

    private InMemoryRepository(
            Class<R> type, Function<T, ID> idOf, Function<T, T> assignId, Map<String, Function<Object[], Object>> finders
    ) {
        this.proxy = type.cast(Proxy.newProxyInstance(
                InMemoryRepository.class.getClassLoader(),
                new Class<?>[]{type},
                (ignored, method, args) -> {
                    Function<Object[], Object> finder = finders.get(method.getName());
                    if (finder != null) {
                        return finder.apply(args == null ? new Object[0] : args);
                    }
                    return switch (method.getName()) {
                        case "findById" -> Optional.ofNullable(this.store.get(args[0]));
                        case "existsById" -> this.store.containsKey(args[0]);
                        case "save", "update" -> {
                            @SuppressWarnings("unchecked") T given = (T) args[0];
                            T entity = idOf.apply(given) == null && assignId != null ? assignId.apply(given) : given;
                            this.store.put(idOf.apply(entity), entity);
                            yield entity;
                        }
                        case "delete" -> {
                            @SuppressWarnings("unchecked") T entity = (T) args[0];
                            this.store.remove(idOf.apply(entity));
                            yield null;
                        }
                        case "deleteById" -> {
                            this.store.remove(args[0]);
                            yield null;
                        }
                        case "findAll" -> args == null || args.length == 0
                                ? new ArrayList<>(this.store.values())
                                : Page.of(new ArrayList<>(this.store.values()), (Pageable) args[0], (long) this.store.size());
                        case "count" -> (long) this.store.size();
                        default -> defaultValue(method.getReturnType());
                    };
                }
        ));
    }

    public static <R, T, ID> Builder<R, T, ID> of(Class<R> type, Function<T, ID> idOf) {
        return new Builder<>(type, idOf);
    }

    /** The repository to hand to the code under test. */
    public R repository() {
        return this.proxy;
    }

    /** Everything stored so far, in insertion order. */
    public List<T> all() {
        return new ArrayList<>(this.store.values());
    }

    /** Puts an entity in the store as if it had been saved earlier. */
    public InMemoryRepository<R, T, ID> given(T entity, ID id) {
        this.store.put(id, entity);
        return this;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == List.class || type == Iterable.class) {
            return new ArrayList<>();
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

    public static final class Builder<R, T, ID> {

        private final Class<R> type;
        private final Function<T, ID> idOf;
        private final Map<String, Function<Object[], Object>> finders = new LinkedHashMap<>();
        private Function<T, T> assignId;

        private Builder(Class<R> type, Function<T, ID> idOf) {
            this.type = type;
            this.idOf = idOf;
        }

        /** Answers calls to the repository method {@code name} with {@code answer}. */
        public Builder<R, T, ID> finder(String name, Function<Object[], Object> answer) {
            this.finders.put(name, answer);
            return this;
        }

        /**
         * Like the database, hands an entity saved without an id a generated one: {@code assignId}
         * returns a copy of the entity that carries an id.
         */
        public Builder<R, T, ID> assigningIds(Function<T, T> assignId) {
            this.assignId = assignId;
            return this;
        }

        public InMemoryRepository<R, T, ID> build() {
            return new InMemoryRepository<>(this.type, this.idOf, this.assignId, this.finders);
        }
    }
}
