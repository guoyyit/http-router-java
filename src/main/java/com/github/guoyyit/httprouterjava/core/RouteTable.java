package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.pattern.CatchAllSegment;
import com.github.guoyyit.httprouterjava.pattern.LiteralSegment;
import com.github.guoyyit.httprouterjava.pattern.ParamSegment;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import com.github.guoyyit.httprouterjava.pattern.Segment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, atomic unit of routing state. Once published, a table is never
 * mutated; hot updates publish a brand new instance, so concurrent readers
 * always observe either the previous table or the new one, never a mix.
 *
 * <p>A {@link RouteTrie} index is built once at construction; lookups visit
 * only trie branches compatible with each request segment, so the cost is
 * driven by the request path depth and branching at that path, not by the
 * total number of registered routes with unrelated prefixes.</p>
 */
public final class RouteTable {

    private final long version;
    private final List<CompiledRoute> routes;
    private final RouteTrie trie;

    public RouteTable(long version, List<CompiledRoute> routes) {
        this.version = version;
        this.routes = List.copyOf(routes);
        RouteTrie built = new RouteTrie();
        for (CompiledRoute route : this.routes) {
            for (List<Segment> alternative : route.getPattern().getAlternatives()) {
                built.insert(alternative, route);
            }
        }
        this.trie = built;
    }

    public long getVersion() {
        return version;
    }

    public List<CompiledRoute> getRoutes() {
        return routes;
    }

    public static final RouteTable EMPTY = new RouteTable(0, List.of());

    /**
     * Find the uniquely-winning route.
     *
     * <p>Priority among all fully-matching routes (method + path + headers):
     * higher explicit {@code priority} first; ties broken by the more specific
     * matched path alternative (literal &gt; param &gt; wildcard, and longer
     * fixed prefixes first); then by more header conditions; an unresolved tie
     * is a {@link ConflictException}.</p>
     *
     * @throws NoMatchException           no route matches the path at all, or
     *                                    header conditions eliminate every method match
     * @throws MethodNotAllowedException  path matched but no route accepts method
     * @throws ConflictException          several routes match with no unique winner
     */
    public MatchResult find(HttpMethod method, String path, Map<String, String> headers) {
        List<String> requestSegments = PathPatternParser.splitRequestPath(path);
        List<RouteTrie.Hit> pathHits = trie.find(requestSegments);

        if (pathHits.isEmpty()) {
            throw new NoMatchException("no route registered for path: " + path);
        }

        Set<String> methodsOfPathMatches = new LinkedHashSet<>();
        List<FullMatch> full = new ArrayList<>();
        for (RouteTrie.Hit hit : pathHits) {
            Route r = hit.route().getRoute();
            methodsOfPathMatches.add(r.getMethod().name());
            if (r.getMethod() == method && hit.route().matchesHeaders(headers)) {
                full.add(new FullMatch(hit.route(), hit.variables(), score(hit.alternative())));
            }
        }

        if (full.isEmpty()) {
            if (methodsOfPathMatches.contains(method.name())) {
                throw new NoMatchException(
                        "path matches route(s) for " + method
                                + " but none satisfies the header conditions");
            }
            throw new MethodNotAllowedException(
                    "method " + method + " is not allowed for path: " + path,
                    List.copyOf(methodsOfPathMatches));
        }

        FullMatch best = full.get(0);
        List<FullMatch> tied = new ArrayList<>();
        tied.add(best);
        for (int i = 1; i < full.size(); i++) {
            FullMatch cur = full.get(i);
            int cmp = compareRank(cur, best);
            if (cmp > 0) {
                best = cur;
                tied.clear();
                tied.add(cur);
            } else if (cmp == 0) {
                tied.add(cur);
            }
        }

        if (tied.size() > 1) {
            List<String> handlers = tied.stream()
                    .map(f -> f.route.getRoute().getHandlerId())
                    .distinct()
                    .sorted()
                    .toList();
            throw new ConflictException(
                    "ambiguous match for " + method + " " + path
                            + " between handlers: " + handlers,
                    handlers);
        }

        Route winner = best.route.getRoute();
        return new MatchResult(winner.getHandlerId(), winner.getPriority(),
                Map.copyOf(best.variables), matchedHeaderValues(best.route, headers));
    }

    private static int compareRank(FullMatch a, FullMatch b) {
        int byPriority = Integer.compare(
                a.route.getRoute().getPriority(), b.route.getRoute().getPriority());
        if (byPriority != 0) {
            return byPriority;
        }
        int bySpecificity = Integer.compare(a.specificity, b.specificity);
        if (bySpecificity != 0) {
            return bySpecificity;
        }
        return Integer.compare(a.route.getHeaders().size(), b.route.getHeaders().size());
    }

    /**
     * Specificity of the matched concrete alternative. Literal, parameter and
     * wildcard segments have descending weights, and a catch-all adds nothing;
     * hence, for equal weights, a longer fixed prefix (no catch-all tail) wins
     * because every fixed segment contributes a positive weight.
     */
    private static int score(List<Segment> alternative) {
        int score = 0;
        for (Segment s : alternative) {
            if (s instanceof LiteralSegment) {
                score += 3;
            } else if (s instanceof ParamSegment) {
                score += 2;
            } else if (s instanceof CatchAllSegment) {
                // catch-all matches zero+ trailing segments: least specific
            } else {
                score += 1; // single-segment wildcard
            }
        }
        return score;
    }

    private static Map<String, String> matchedHeaderValues(CompiledRoute route,
                                                           Map<String, String> request) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var condition : route.getHeaders()) {
            for (Map.Entry<String, String> e : request.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(condition.getName())) {
                    out.put(e.getKey(), e.getValue());
                    break;
                }
            }
        }
        return Map.copyOf(out);
    }

    /** Raw (uncompiled) routes, e.g. for serialization. */
    public List<Route> rawRoutes() {
        return routes.stream().map(CompiledRoute::getRoute).toList();
    }

    private record FullMatch(CompiledRoute route, Map<String, String> variables,
                             int specificity) {
    }
}
