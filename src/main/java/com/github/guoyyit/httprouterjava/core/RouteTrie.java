package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.pattern.CatchAllSegment;
import com.github.guoyyit.httprouterjava.pattern.LiteralSegment;
import com.github.guoyyit.httprouterjava.pattern.ParamSegment;
import com.github.guoyyit.httprouterjava.pattern.Segment;
import com.github.guoyyit.httprouterjava.pattern.WildcardSegment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only path index for a {@link RouteTable}.
 *
 * <p>Each concrete pattern alternative is inserted into a segment trie with
 * three branch kinds per node: literal children, one parameter branch and one
 * single-segment wildcard branch, plus at most one catch-all. A lookup walks
 * only branches that can accept each request segment, so the visited-node
 * count is bounded by the number of structural possibilities at the request
 * path's depth &mdash; independent of the total number of registered routes
 * with disjoint prefixes. Lookup returns only structurally-matching routes;
 * method and header checks stay in {@link RouteTable}.</p>
 */
final class RouteTrie {

    private final Node root = new Node();

    void insert(List<Segment> segments, CompiledRoute route) {
        Node current = root;
        int last = segments.size() - 1;
        boolean catchAll = last >= 0 && segments.get(last) instanceof CatchAllSegment;
        int fixedLen = catchAll ? last : segments.size();
        for (int i = 0; i < fixedLen; i++) {
            current = current.child(segments.get(i));
        }
        if (catchAll) {
            CatchAllSegment c = (CatchAllSegment) segments.get(last);
            current.catchAll.add(new Endpoint(route, c.variable(), List.copyOf(segments)));
        } else {
            current.exact.add(new Endpoint(route, null, List.copyOf(segments)));
        }
    }

    /**
     * Collect routes whose pattern structurally matches the request segments,
     * together with extracted path variables. No allocations are performed for
     * routes that cannot match.
     */
    List<Hit> find(List<String> request) {
        List<Hit> hits = new ArrayList<>();
        Map<String, String> vars = new LinkedHashMap<>();
        walk(root, request, 0, vars, hits);
        return hits;
    }

    private void walk(Node node, List<String> request, int depth,
                      Map<String, String> vars, List<Hit> hits) {
        if (node == null) {
            return;
        }
        // Catch-all at this prefix matches zero or more remaining segments.
        for (Endpoint ep : node.catchAll) {
            Map<String, String> withTail = new LinkedHashMap<>(vars);
            withTail.put(ep.catchAllName(), String.join("/", request.subList(depth, request.size())));
            hits.add(new Hit(ep.route(), withTail, ep.alternative()));
        }
        if (depth == request.size()) {
            for (Endpoint ep : node.exact) {
                hits.add(new Hit(ep.route(), vars, ep.alternative()));
            }
            return;
        }
        String token = request.get(depth);

        Node literal = node.literals.get(token);
        if (literal != null) {
            walk(literal, request, depth + 1, vars, hits);
        }
        for (Map.Entry<ParamSegment, Node> e : node.params.entrySet()) {
            ParamSegment p = e.getKey();
            if (p.constraint() == null || p.constraint().matcher(token).matches()) {
                Map<String, String> next = new LinkedHashMap<>(vars);
                next.put(p.name(), token);
                walk(e.getValue(), request, depth + 1, next, hits);
            }
        }
        if (node.wildcard != null) {
            walk(node.wildcard, request, depth + 1, vars, hits);
        }
    }

    private static final class Node {
        private final Map<String, Node> literals = new LinkedHashMap<>();
        private final Map<ParamSegment, Node> params = new LinkedHashMap<>();
        private Node wildcard;
        private final List<Endpoint> exact = new ArrayList<>();
        private final List<Endpoint> catchAll = new ArrayList<>();

        private Node child(Segment segment) {
            if (segment instanceof LiteralSegment l) {
                return literals.computeIfAbsent(l.value(), k -> new Node());
            }
            if (segment instanceof ParamSegment p) {
                Node existing = paramNode(p);
                if (existing != null) {
                    return existing;
                }
                Node created = new Node();
                params.put(p, created);
                return created;
            }
            if (segment instanceof WildcardSegment) {
                if (wildcard == null) {
                    wildcard = new Node();
                }
                return wildcard;
            }
            throw new IllegalStateException("unexpected fixed segment: " + segment);
        }

        private Node paramNode(ParamSegment wanted) {
            for (ParamSegment existing : params.keySet()) {
                if (existing.name().equals(wanted.name())
                        && sameConstraint(existing, wanted)) {
                    return params.get(existing);
                }
            }
            return null;
        }

        private static boolean sameConstraint(ParamSegment a, ParamSegment b) {
            String pa = a.constraint() == null ? null : a.constraint().pattern();
            String pb = b.constraint() == null ? null : b.constraint().pattern();
            return java.util.Objects.equals(pa, pb);
        }
    }

    private record Endpoint(CompiledRoute route, String catchAllName,
                            List<Segment> alternative) {
    }

    record Hit(CompiledRoute route, Map<String, String> variables,
               List<Segment> alternative) {
    }
}
