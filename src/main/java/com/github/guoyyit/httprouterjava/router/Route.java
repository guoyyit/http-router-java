package com.github.guoyyit.httprouterjava.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One immutable route entry. */
public record Route(
        String id,
        HttpMethod method,
        RoutePattern pattern,
        List<HeaderCondition> headerConditions,
        String handlerId,
        int priority) {

    private static final String DEFAULT_ID_PREFIX = "route-";

    public Route {
        if (id == null || id.isBlank()) {
            throw new PatternParseException("Route id must not be blank");
        }
        if (method == null) {
            throw new PatternParseException("Route method must not be null");
        }
        if (pattern == null) {
            throw new PatternParseException("Route pattern must not be null");
        }
        if (handlerId == null || handlerId.isBlank()) {
            throw new PatternParseException("Route handlerId must not be blank");
        }
        headerConditions = List.copyOf(headerConditions);
        // reject self-contradicting header conditions
        for (int i = 0; i < headerConditions.size(); i++) {
            for (int j = i + 1; j < headerConditions.size(); j++) {
                if (headerConditions.get(i).contradicts(headerConditions.get(j))) {
                    throw new PatternParseException("Contradicting header conditions on '"
                            + headerConditions.get(i).name() + "'");
                }
            }
        }
    }

    /** Parse-based factory shared by the REST layer, tests and persistence. */
    public static Route create(String id, String method, String pattern,
                               Map<String, String> headers, String handlerId, Integer priority) {
        if (handlerId == null || handlerId.isBlank()) {
            handlerId = id; // default handler identity == route identity
        }
        List<HeaderCondition> conditions = new ArrayList<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conditions.add(HeaderCondition.parse(e.getKey(), e.getValue()));
            }
        }
        String rid = (id == null || id.isBlank())
                ? DEFAULT_ID_PREFIX + Integer.toHexString((method + " " + pattern).hashCode())
                : id;
        return new Route(rid, HttpMethod.parse(method), RoutePattern.parse(pattern),
                conditions, handlerId, priority == null ? 0 : priority);
    }

    public boolean matches(HttpMethod m, List<String> pathSegments, Map<String, String> headers) {
        if (!method.matches(m)) {
            return false;
        }
        if (!matchesPath(pathSegments)) {
            return false;
        }
        for (HeaderCondition c : headerConditions) {
            if (!c.matches(headers)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesPath(List<String> path) {
        List<Segment> segs = pattern.segments();
        int wildcard = (segs.size() > 0 && segs.get(segs.size() - 1) instanceof Segment.WildcardSeg)
                ? segs.size() - 1 : -1;
        if (wildcard >= 0) {
            if (path.size() < wildcard) {
                return false;
            }
            for (int i = 0; i < wildcard; i++) {
                if (!segs.get(i).matches(path.get(i))) {
                    return false;
                }
            }
            return true;
        }
        int optionals = 0;
        for (int i = segs.size() - 1; i >= 0 && segs.get(i) instanceof Segment.OptionalSeg; i--) {
            optionals++;
        }
        int required = segs.size() - optionals;
        if (path.size() < required || path.size() > segs.size()) {
            return false;
        }
        for (int i = 0; i < path.size(); i++) {
            if (!segs.get(i).matches(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Identity key for duplicate detection: same method, pattern and header
     * conditions, regardless of handler or explicit priority.
     */
    public boolean sameKey(Route other) {
        return method == other.method
                && pattern.raw().equals(other.pattern.raw())
                && headerConditions.equals(other.headerConditions);
    }
}
