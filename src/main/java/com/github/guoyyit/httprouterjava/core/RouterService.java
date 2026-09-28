package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.model.Route;

import java.util.List;
import java.util.Map;

/**
 * Read/write entry point of the routing service.
 */
public interface RouterService {

    /** Match a request; never silently falls back to an arbitrary handler. */
    MatchResult match(HttpMethod method, String path, Map<String, String> headers);

    /** Atomically add one route; rejects duplicates and conflicts, keeps old table. */
    void addRoute(Route route);

    /** Atomically remove one route by handler id; missing id is an error. */
    void removeRoute(String handlerId);

    /** Atomically replace the whole table; validates the new set first. */
    void replaceAll(List<Route> routes);

    /** Current committed version. */
    long currentVersion();

    /** Snapshot of the committed routes. */
    List<Route> snapshot();
}
