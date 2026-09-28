package com.github.guoyyit.httprouterjava.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a new immutable RouteTable from an existing table plus a batch of
 * additions/removals. All validation (duplicate ids, duplicate routes,
 * priority/pattern-overlap conflicts) happens before build(); on any violation
 * nothing is applied and the previous table stays in place.
 */
public final class RouteTableBuilder {

    private final Map<String, Route> byId = new LinkedHashMap<>();
    private final List<Route> additions = new ArrayList<>();
    private final List<String> removals = new ArrayList<>();
    private boolean validated = false;
    private RouteTable built;

    public RouteTableBuilder(RouteTable base) {
        for (Route r : base.routes()) {
            byId.put(r.id(), r);
        }
    }

    public RouteTableBuilder add(Route route) {
        if (route == null) {
            throw new PatternParseException("Route must not be null");
        }
        additions.add(route);
        return this;
    }

    public RouteTableBuilder remove(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            throw new PatternParseException("Route id must not be blank");
        }
        removals.add(routeId);
        return this;
    }

    /** Validate the pending changes; removals are applied first, then additions (replace semantics). */
    public void validate() {
        if (validated) {
            return;
        }
        // 1. removals must reference routes currently registered
        for (String id : removals) {
            if (!byId.containsKey(id)) {
                throw new RouteNotFoundException("Route not found: " + id);
            }
        }
        // 2. apply additions onto the staged result; duplicate ids are rejected
        Map<String, Route> result = byIdWithChanges();
        for (Route r : additions) {
            if (result.put(r.id(), r) != null) {
                throw new RouteConflictException("Duplicate route id: " + r.id());
            }
        }
        // 3. build the new table incrementally: trie-bounded overlap/conflict validation
        built = RouteTable.buildValidated(new ArrayList<>(result.values()));
        validated = true;
    }

    /**
     * Two routes conflict when some request could match both simultaneously and
     * neither has strictly higher priority. Header conditions that contradict
     * each other make simultaneous matching impossible, hence not a conflict.
     */
    static String conflictReason(Route a, Route b) {
        if (!methodOverlaps(a, b)) {
            return null;
        }
        if (!a.pattern().overlaps(b.pattern())) {
            return null;
        }
        if (headersContradict(a, b)) {
            return null;
        }
        if (a.priority() != b.priority()) {
            return null; // explicit priority disambiguates
        }
        if (a.pattern().compareSpecificity(b.pattern()) != 0) {
            return null; // path specificity disambiguates
        }
        if (a.headerConditions().size() != b.headerConditions().size()) {
            return null; // more header conditions disambiguate
        }
        boolean aAny = a.method() == HttpMethod.ANY;
        boolean bAny = b.method() == HttpMethod.ANY;
        if (aAny != bAny) {
            return null; // method-specific route disambiguates
        }
        return "patterns can match the same request with equal priority and specificity";
    }

    private static boolean methodOverlaps(Route a, Route b) {
        return a.method() == HttpMethod.ANY || b.method() == HttpMethod.ANY || a.method() == b.method();
    }

    private static boolean headersContradict(Route a, Route b) {
        for (HeaderCondition c1 : a.headerConditions()) {
            for (HeaderCondition c2 : b.headerConditions()) {
                if (c1.contradicts(c2)) {
                    return true;
                }
            }
        }
        return false;
    }

    public RouteTable build() {
        validate();
        return built;
    }

    private Map<String, Route> byIdWithChanges() {
        Map<String, Route> result = new LinkedHashMap<>(byId);
        removals.forEach(result::remove);
        return result;
    }
}
