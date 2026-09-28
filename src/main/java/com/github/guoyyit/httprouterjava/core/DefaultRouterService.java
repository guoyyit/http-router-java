package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.persist.Snapshot;
import com.github.guoyyit.httprouterjava.persist.SnapshotStore;
import com.github.guoyyit.httprouterjava.pattern.PathPattern;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link RouterService}.
 *
 * <p>Concurrency model: the committed table lives in a single
 * {@link AtomicReference}. Readers call {@code get()} once and run their whole
 * lookup against that immutable snapshot, so writes never block reads and a
 * reader always sees exactly one generation. Writers serialize on an internal
 * lock, validate the full new set, build a new immutable {@link RouteTable},
 * persist it durably and only then swap the reference. A failed validation or
 * persistence leaves the committed table exactly as it was.</p>
 */
@Service
public class DefaultRouterService implements RouterService {

    private final AtomicReference<RouteTable> table = new AtomicReference<>(RouteTable.EMPTY);
    private final Object writeLock = new Object();

    private final PathPatternParser parser;
    private final RouteValidator validator;
    private final SnapshotStore snapshotStore;

    public DefaultRouterService(PathPatternParser parser,
                                RouteValidator validator,
                                SnapshotStore snapshotStore) {
        this.parser = parser;
        this.validator = validator;
        this.snapshotStore = snapshotStore;
    }

    @Override
    public MatchResult match(HttpMethod method, String path, Map<String, String> headers) {
        RouteTable snapshot = table.get();
        return snapshot.find(method, path, headers == null ? Map.of() : headers);
    }

    @Override
    public void addRoute(Route route) {
        synchronized (writeLock) {
            List<Route> next = new ArrayList<>(table.get().rawRoutes());
            next.add(route);
            commit(next);
        }
    }

    @Override
    public void removeRoute(String handlerId) {
        synchronized (writeLock) {
            List<Route> current = table.get().rawRoutes();
            boolean removed = current.stream()
                    .anyMatch(r -> r.getHandlerId().equals(handlerId));
            if (!removed) {
                throw new NoMatchException("no route with handlerId: " + handlerId);
            }
            List<Route> next = current.stream()
                    .filter(r -> !r.getHandlerId().equals(handlerId))
                    .toList();
            commit(next);
        }
    }

    @Override
    public void replaceAll(List<Route> routes) {
        synchronized (writeLock) {
            commit(routes == null ? List.of() : new ArrayList<>(routes));
        }
    }

    @Override
    public long currentVersion() {
        return table.get().getVersion();
    }

    @Override
    public List<Route> snapshot() {
        return table.get().rawRoutes();
    }

    /**
     * Restore the latest persisted snapshot on startup. A corrupt snapshot is
     * ignored in favor of an older valid one; recovery either fully succeeds
     * or leaves the empty table &mdash; there is no half-applied state.
     */
    public void restoreLatest() {
        synchronized (writeLock) {
            Snapshot latest = snapshotStore.loadLatest();
            if (latest == null) {
                table.set(RouteTable.EMPTY);
                return;
            }
            List<PathPattern> patterns = validator.validateSet(latest.routes());
            table.set(buildTable(latest.version(), latest.routes(), patterns));
        }
    }

    /**
     * Roll the live table back to a previously persisted version. The chosen
     * version is re-validated, re-persisted as a new commit and then published
     * atomically.
     */
    public Snapshot rollback(long version) {
        synchronized (writeLock) {
            Snapshot target = snapshotStore.rollbackTo(version);
            long newVersion = table.get().getVersion() + 1;
            List<PathPattern> patterns = validator.validateSet(target.routes());
            snapshotStore.save(newVersion, target.routes());
            table.set(buildTable(newVersion, target.routes(), patterns));
            return new Snapshot(newVersion, target.routes());
        }
    }

    /**
     * Validate &rarr; build &rarr; persist &rarr; publish. Any failure before
     * the final reference swap leaves the currently committed table intact.
     */
    private void commit(List<Route> routes) {
        List<PathPattern> patterns = validator.validateSet(routes);
        long newVersion = table.get().getVersion() + 1;
        RouteTable built = buildTable(newVersion, routes, patterns);
        snapshotStore.save(newVersion, routes); // durable first
        table.set(built);
    }

    private RouteTable buildTable(long version, List<Route> routes,
                                  List<PathPattern> patterns) {
        List<CompiledRoute> compiled = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            compiled.add(new CompiledRoute(routes.get(i), patterns.get(i)));
        }
        return new RouteTable(version, compiled);
    }
}
