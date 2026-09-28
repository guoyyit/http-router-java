package com.github.guoyyit.httprouterjava.router;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe router facade.
 *
 * Concurrency: the active table is a volatile reference, so readers never block
 * on writers and always see either the old table or the new one, never a partial
 * table. Writers are serialized by a monitor; validation happens on a freshly
 * built table before the reference is swapped, so a rejected update leaves the
 * old table intact.
 *
 * Snapshots: every committed table is kept in an in-memory history (bounded) for
 * rollback; the newest committed table is persisted by {@link RouteSnapshotStore}
 * and restored on startup.
 */
public class RouterService {

    private static final int MAX_HISTORY = 20;

    private final RouteSnapshotStore store;
    private final Object writeLock = new Object();
    private final Deque<List<Route>> undoStack = new ArrayDeque<>();
    private final Deque<List<Route>> redoStack = new ArrayDeque<>();
    private volatile RouteTable current = new RouteTable(List.of());
    private volatile boolean recovered = false;

    public RouterService(RouteSnapshotStore store) {
        this.store = store;
    }

    /** Load the last committed table from the store; safe to call once at startup. */
    public void recover() {
        synchronized (writeLock) {
            if (recovered) {
                return;
            }
            RouteSnapshotStore.Recovered recoveredState = store.recover();
            current = new RouteTable(recoveredState.current());
            undoStack.clear();
            for (List<Route> t : recoveredState.undoHistory()) {
                undoStack.add(new ArrayList<>(t));
            }
            redoStack.clear();
            recovered = true;
        }
    }

    public MatchResult match(String method, String path, Map<String, String> headers) {
        HttpMethod m = HttpMethod.parse(method);
        Map<String, String> h = normalizeHeaders(headers);
        return current.lookup(m, path, h);
    }

    /** Atomically apply additions and removals; on any conflict nothing changes. */
    public void update(List<Route> additions, List<String> removals) {
        List<Route> add = additions == null ? List.of() : new ArrayList<>(additions);
        List<String> rem = removals == null ? List.of() : new ArrayList<>(removals);
        synchronized (writeLock) {
            RouteTableBuilder builder = new RouteTableBuilder(current);
            for (String id : rem) {
                builder.remove(id);
            }
            for (Route r : add) {
                builder.add(r);
            }
            RouteTable next = builder.build(); // validates first; throws -> old table retained

            List<Route> previous = current.routes();
            while (undoStack.size() >= MAX_HISTORY) {
                undoStack.removeLast();
            }
            undoStack.push(previous);
            redoStack.clear(); // a new commit invalidates the redo branch
            store.saveAll(next.routes(), snapshotStack()); // atomic file commit before in-memory swap
            current = next;
        }
    }

    public void addRoute(Route route) {
        update(List.of(route), List.of());
    }

    public void removeRoute(String routeId) {
        update(List.of(), List.of(routeId));
    }

    /**
     * Roll back to the previous committed snapshot (undo). Can be called
     * repeatedly down to the recovered/initial table. A fresh update after a
     * rollback clears the redo branch.
     */
    public void rollback() {
        synchronized (writeLock) {
            if (undoStack.isEmpty()) {
                throw new IllegalStateException("No previous snapshot to roll back to");
            }
            redoStack.push(current.routes());
            List<Route> previous = undoStack.pop();
            store.saveAll(previous, snapshotStack()); // atomic file commit before in-memory swap
            current = new RouteTable(previous);
        }
    }

    /** Undo stack snapshot in pop-order (head = most recent previous table). */
    private List<List<Route>> snapshotStack() {
        List<List<Route>> out = new ArrayList<>(undoStack.size());
        for (List<Route> t : undoStack) {
            out.add(new ArrayList<>(t));
        }
        return out;
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toLowerCase(), e.getValue());
            }
        }
        return out;
    }

    public List<Route> routes() {
        return current.routes();
    }

    public int size() {
        return current.size();
    }

    public int snapshotCount() {
        synchronized (writeLock) {
            return undoStack.size();
        }
    }
}
