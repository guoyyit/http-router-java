package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.model.HeaderCondition;
import com.github.guoyyit.httprouterjava.model.HeaderMatchType;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.pattern.PathPattern;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates individual route definitions and whole route sets:
 * field presence, pattern syntax, regex syntax, uniqueness of handler ids and
 * exact duplicates, and overlap conflicts between equal-priority routes.
 *
 * <p>Conflict policy at registration time:</p>
 * <ul>
 *   <li>duplicate handler id or an identical (method, path, headers) route is
 *       always rejected;</li>
 *   <li>two routes whose header conditions are <em>compatible</em> (i.e. some
 *       request satisfies both) and whose paths overlap must declare different
 *       {@code priority}; equal-priority overlaps are rejected;</li>
 *   <li>mutually exclusive header conditions (e.g. {@code X=v1} vs
 *       {@code X=v2}) or disjoint methods never conflict, regardless of
 *       priority.</li>
 * </ul>
 * A rejected set leaves the previously committed table untouched.
 */
@Component
public class RouteValidator {

    private final PathPatternParser parser;

    public RouteValidator(PathPatternParser parser) {
        this.parser = parser;
    }

    public void validateOne(Route route) {
        if (route == null) {
            throw new InvalidRouteException("route must not be null");
        }
        if (route.getHandlerId() == null || route.getHandlerId().isBlank()) {
            throw new InvalidRouteException("handlerId must not be blank");
        }
        if (route.getMethod() == null) {
            throw new InvalidRouteException(
                    "method must not be null for route " + route.getHandlerId());
        }
        if (route.getPath() == null || route.getPath().isBlank()) {
            throw new InvalidRouteException(
                    "path must not be blank for route " + route.getHandlerId());
        }
        parser.parse(route.getPath()); // throws InvalidRouteException on bad syntax
        validateHeaders(route.getHeaders(), route.getHandlerId());
    }

    /**
     * Validate the whole candidate set.
     *
     * @return compiled path patterns keyed by list index, ready for table build
     */
    public List<PathPattern> validateSet(List<Route> routes) {
        if (routes == null) {
            throw new InvalidRouteException("routes must not be null");
        }
        List<PathPattern> patterns = new ArrayList<>(routes.size());
        Set<String> handlerIds = new HashSet<>();
        Set<String> exactSignatures = new HashSet<>();

        for (Route route : routes) {
            validateOne(route);
            if (!handlerIds.add(route.getHandlerId())) {
                throw new ConflictException(
                        "duplicate handlerId: " + route.getHandlerId(),
                        List.of(route.getHandlerId()));
            }
            String signature = signature(route);
            if (!exactSignatures.add(signature)) {
                throw new ConflictException(
                        "duplicate route registration (same method/path/headers): "
                                + signature,
                        List.of(route.getHandlerId()));
            }
            patterns.add(parser.parse(route.getPath()));
        }

        OverlapIndex index = new OverlapIndex();
        for (int i = 0; i < routes.size(); i++) {
            index.add(patterns.get(i), routes.get(i));
        }
        return patterns;
    }

    /**
     * Conservative compatibility: could one request satisfy both condition
     * lists? Different required values for the same header name, or PRESENT vs
     * ABSENT on the same name, make them mutually exclusive.
     */
    static boolean headersCompatible(List<HeaderCondition> a, List<HeaderCondition> b) {
        Map<String, Requirement> ra = requirements(a);
        Map<String, Requirement> rb = requirements(b);
        for (Map.Entry<String, Requirement> e : ra.entrySet()) {
            Requirement other = rb.get(e.getKey());
            if (other != null && !e.getValue().compatibleWith(other)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Requirement> requirements(List<HeaderCondition> list) {
        Map<String, Requirement> out = new HashMap<>();
        for (HeaderCondition c : list) {
            String name = c.getName().toLowerCase();
            Requirement req = switch (c.getType()) {
                case ABSENT -> Requirement.ABSENT;
                case PRESENT -> Requirement.PRESENT;
                case EXACT -> new Requirement(c.getValue() == null ? "" : c.getValue().toLowerCase());
                case REGEX -> Requirement.PRESENT; // conservative: treated as just present
            };
            Requirement merged = out.get(name);
            out.put(name, merged == null ? req : merged.combine(req));
        }
        return out;
    }

    private void validateHeaders(List<HeaderCondition> headers, String handlerId) {
        if (headers == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (HeaderCondition c : headers) {
            if (c == null) {
                throw new InvalidRouteException(
                        "null header condition on route " + handlerId);
            }
            if (c.getName() == null || c.getName().isBlank()) {
                throw new InvalidRouteException(
                        "header name must not be blank on route " + handlerId);
            }
            if (c.getType() == null) {
                throw new InvalidRouteException(
                        "header match type must not be null for '" + c.getName()
                                + "' on route " + handlerId);
            }
            if ((c.getType() == HeaderMatchType.EXACT || c.getType() == HeaderMatchType.REGEX)
                    && (c.getValue() == null || c.getValue().isEmpty())) {
                throw new InvalidRouteException(
                        c.getType() + " header condition on '" + c.getName()
                                + "' needs a value (route " + handlerId + ")");
            }
            if (c.getType() == HeaderMatchType.REGEX) {
                try {
                    Pattern.compile(c.getValue());
                } catch (PatternSyntaxException e) {
                    throw new InvalidRouteException(
                            "invalid header regex '" + c.getValue() + "' on route "
                                    + handlerId, e);
                }
            }
            if (!seen.add(c.getName().toLowerCase() + ":" + c.getType())) {
                throw new InvalidRouteException(
                        "duplicate header condition on '" + c.getName() + "' ("
                                + c.getType() + ") for route " + handlerId);
            }
        }
    }

    private static String signature(Route r) {
        List<String> headerSignatures = r.getHeaders().stream()
                .map(c -> c.getName().toLowerCase() + "|" + c.getType()
                        + "|" + (c.getValue() == null ? "" : c.getValue()))
                .sorted()
                .toList();
        // Priority is part of the signature: re-registering the same
        // method/path/headers at a *different* priority is an allowed explicit
        // override rather than a duplicate.
        return r.getMethod() + " " + r.getPath() + " " + headerSignatures
                + " p=" + r.getPriority();
    }

    /** What one route requires of a single header name. */
    private static final class Requirement {
        static final Requirement PRESENT = new Requirement(null, false, false);
        static final Requirement ABSENT = new Requirement(null, true, false);

        private final String exactValue; // null unless EXACT
        private final boolean absent;
        private final boolean exact;

        private Requirement(String exactValue) {
            this(exactValue, false, true);
        }

        private Requirement(String exactValue, boolean absent, boolean exact) {
            this.exactValue = exactValue;
            this.absent = absent;
            this.exact = exact;
        }

        boolean compatibleWith(Requirement o) {
            if (absent || o.absent) {
                return false; // ABSENT vs anything
            }
            if (exact && o.exact) {
                return exactValue.equals(o.exactValue);
            }
            return true; // exact vs present/regex conservatively compatible
        }

        Requirement combine(Requirement o) {
            // Multiple conditions on the same header inside one route:
            // exact+present => exact; exact+absent is impossible.
            if (absent || o.absent) {
                return ABSENT;
            }
            if (exact) {
                return this;
            }
            return o;
        }
    }
}
