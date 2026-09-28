package com.github.guoyyit.httprouterjava.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable, snapshot-able route table backed by a segment trie.
 * Lookups never block on writers: writers build a new table and swap the
 * reference atomically. Typical lookup cost is O(path depth x regex branches)
 * plus candidate comparison, independent of total table size.
 */
public final class RouteTable {

    private final List<Route> routes;
    private final Node root;

    /** Build a table and construct its trie from the routes. */
    public RouteTable(List<Route> routes) {
        this(routes, freshTrie(routes));
    }

    private RouteTable(List<Route> routes, Node trieRoot) {
        this.routes = List.copyOf(routes);
        this.root = trieRoot;
    }

    private static Node freshTrie(List<Route> routes) {
        Node node = new Node();
        for (Route route : routes) {
            for (List<Segment> expansion : route.pattern().expand()) {
                insert(node, expansion, 0, route);
            }
        }
        return node;
    }

    /**
     * Build a validated table incrementally. For each insertion only trie nodes
     * that could lead to an overlapping pattern are visited (same traversal shape
     * as a lookup), so conflict checking is path-depth bounded rather than
     * linear in total table size. Throws on duplicate id/key or ambiguity.
     */
    public static RouteTable buildValidated(List<Route> ordered) {
        Node trie = new Node();
        Map<String, Route> ids = new HashMap<>();
        for (Route candidate : ordered) {
            if (ids.containsKey(candidate.id())) {
                throw new RouteConflictException("Duplicate route id: " + candidate.id());
            }
            for (List<Segment> expansion : candidate.pattern().expand()) {
                List<Route> overlapping = new ArrayList<>();
                collectOverlap(trie, expansion, 0, overlapping);
                for (Route existing : overlapping) {
                    if (candidate.sameKey(existing)) {
                        throw new RouteConflictException(
                                "Duplicate registration: route " + candidate.id()
                                        + " duplicates " + existing.id() + " (" + keyOf(candidate) + ")");
                    }
                    String reason = RouteTableBuilder.conflictReason(candidate, existing);
                    if (reason != null) {
                        throw new RouteConflictException(
                                "Route " + candidate.id() + " (" + keyOf(candidate) + ") conflicts with "
                                        + existing.id() + " (" + keyOf(existing) + "): " + reason);
                    }
                }
                insert(trie, expansion, 0, candidate);
            }
            ids.put(candidate.id(), candidate);
        }
        return new RouteTable(ordered, trie);
    }

    private static String keyOf(Route r) {
        return r.method() + " " + r.pattern().raw();
    }

    /** Collect routes already in the trie whose pattern may overlap the given expansion. */
    private static void collectOverlap(Node node, List<Segment> segs, int i, List<Route> out) {
        if (!node.wildcardRoutes.isEmpty()) {
            out.addAll(node.wildcardRoutes);
        }
        if (i == segs.size()) {
            out.addAll(node.routes);
            // a wildcard anywhere below this node also overlaps the shorter pattern
            collectWildcards(node, out);
            return;
        }
        Segment seg = segs.get(i);
        if (seg instanceof Segment.StaticSeg s) {
            if (node.staticChildren.containsKey(s.value())) {
                collectOverlap(node.staticChildren.get(s.value()), segs, i + 1, out);
            }
            for (RegexBranch rb : node.regexNodes) {
                if (rb.pattern.matcher(s.value()).matches()) {
                    collectOverlap(rb.node, segs, i + 1, out);
                }
            }
            if (node.paramNode != null) {
                collectOverlap(node.paramNode, segs, i + 1, out);
            }
        } else if (seg instanceof Segment.RegexSeg r) {
            // conservative: static children could match the regex; regex/param branches always could
            for (Node child : node.staticChildren.values()) {
                collectOverlap(child, segs, i + 1, out);
            }
            for (RegexBranch rb : node.regexNodes) {
                collectOverlap(rb.node, segs, i + 1, out);
            }
            if (node.paramNode != null) {
                collectOverlap(node.paramNode, segs, i + 1, out);
            }
        } else if (seg instanceof Segment.ParamSeg) {
            for (Node child : node.staticChildren.values()) {
                collectOverlap(child, segs, i + 1, out);
            }
            for (RegexBranch rb : node.regexNodes) {
                collectOverlap(rb.node, segs, i + 1, out);
            }
            if (node.paramNode != null) {
                collectOverlap(node.paramNode, segs, i + 1, out);
            }
        } else if (seg instanceof Segment.WildcardSeg) {
            // wildcard matches everything below: every route in the subtree overlaps
            collectSubtree(node, out);
        }
    }

    private static void collectWildcards(Node node, List<Route> out) {
        out.addAll(node.wildcardRoutes);
        for (Node child : node.staticChildren.values()) {
            collectWildcards(child, out);
        }
        for (RegexBranch rb : node.regexNodes) {
            collectWildcards(rb.node, out);
        }
        if (node.paramNode != null) {
            collectWildcards(node.paramNode, out);
        }
    }

    private static void collectSubtree(Node node, List<Route> out) {
        out.addAll(node.routes);
        out.addAll(node.wildcardRoutes);
        for (Node child : node.staticChildren.values()) {
            collectSubtree(child, out);
        }
        for (RegexBranch rb : node.regexNodes) {
            collectSubtree(rb.node, out);
        }
        if (node.paramNode != null) {
            collectSubtree(node.paramNode, out);
        }
    }

    private static void insert(Node node, List<Segment> segs, int i, Route route) {
        if (i == segs.size()) {
            node.routes.add(route);
            return;
        }
        Segment seg = segs.get(i);
        if (seg instanceof Segment.StaticSeg s) {
            insert(node.staticChildren.computeIfAbsent(s.value(), k -> new Node()),
                    segs, i + 1, route);
        } else if (seg instanceof Segment.ParamSeg) {
            if (node.paramNode == null) {
                node.paramNode = new Node();
            }
            insert(node.paramNode, segs, i + 1, route);
        } else if (seg instanceof Segment.RegexSeg r) {
            Node next = null;
            for (RegexBranch rb : node.regexNodes) {
                if (rb.pattern.pattern().equals(r.regex().pattern())) {
                    next = rb.node;
                    break;
                }
            }
            if (next == null) {
                next = new Node();
                node.regexNodes.add(new RegexBranch(r.regex(), next));
            }
            insert(next, segs, i + 1, route);
        } else if (seg instanceof Segment.WildcardSeg) {
            node.wildcardRoutes.add(route);
        } else {
            throw new PatternParseException("Unexpected segment after expansion: " + seg);
        }
    }

    public MatchResult lookup(HttpMethod method, String path, Map<String, String> headers) {
        List<String> parts = splitPath(path);
        Map<String, String> h = headers == null ? Map.of() : headers;

        List<Route> terminal = new ArrayList<>();
        List<Route> wildcardHits = new ArrayList<>();
        collect(root, parts, 0, terminal, wildcardHits);

        // exact-length terminals are the primary candidates; wildcard matches fill in behind
        List<Route> all = new ArrayList<>(terminal);
        for (Route r : wildcardHits) {
            if (!all.contains(r)) {
                all.add(r);
            }
        }

        List<Route> methodFailures = new ArrayList<>();
        List<Route> headerFailures = new ArrayList<>();
        List<Route> survivors = new ArrayList<>();

        for (Route r : all) {
            if (!r.method().matches(method)) {
                methodFailures.add(r);
                continue;
            }
            if (!headersSatisfied(r, h)) {
                headerFailures.add(r);
                continue;
            }
            survivors.add(r);
        }

        if (survivors.isEmpty()) {
            return missReason(method, terminal, methodFailures, headerFailures, path);
        }

        survivors.sort(FULL_ORDER);
        Route best = survivors.get(0);
        if (survivors.size() > 1 && PRIORITY_ORDER.compare(best, survivors.get(1)) == 0) {
            List<Route> tied = new ArrayList<>();
            for (Route r : survivors) {
                if (PRIORITY_ORDER.compare(best, r) == 0) {
                    tied.add(r);
                }
            }
            return new MatchResult.Conflict(tied,
                    "Ambiguous match: " + tied.size() + " routes with equal priority");
        }
        return new MatchResult.Hit(best);
    }

    private static boolean headersSatisfied(Route r, Map<String, String> h) {
        for (HeaderCondition c : r.headerConditions()) {
            if (!c.matches(h)) {
                return false;
            }
        }
        return true;
    }

    private MatchResult missReason(HttpMethod method, List<Route> terminal,
                                   List<Route> methodFailures, List<Route> headerFailures, String path) {
        int totalCandidates = methodFailures.size() + headerFailures.size();
        if (!methodFailures.isEmpty() && methodFailures.size() == totalCandidates) {
            List<String> ms = methodFailures.stream().map(r -> r.method().name()).distinct().toList();
            return new MatchResult.NoMatch("No route for method " + method
                    + " on path " + path + "; registered methods: " + ms);
        }
        if (!headerFailures.isEmpty()) {
            return new MatchResult.NoMatch("Header conditions not satisfied for path " + path);
        }
        if (!terminal.isEmpty()) {
            return new MatchResult.NoMatch("No route matched path " + path);
        }
        return new MatchResult.NoMatch("No route matches path " + path);
    }

    private void collect(Node node, List<String> parts, int i,
                         List<Route> terminal, List<Route> wildcardHits) {
        // a wildcard attached here absorbs any remaining segments
        if (!node.wildcardRoutes.isEmpty()) {
            wildcardHits.addAll(node.wildcardRoutes);
        }
        if (i == parts.size()) {
            terminal.addAll(node.routes);
            return;
        }
        String seg = parts.get(i);
        Node staticNext = node.staticChildren.get(seg);
        if (staticNext != null) {
            collect(staticNext, parts, i + 1, terminal, wildcardHits);
        }
        for (RegexBranch rb : node.regexNodes) {
            if (rb.pattern.matcher(seg).matches()) {
                collect(rb.node, parts, i + 1, terminal, wildcardHits);
            }
        }
        if (node.paramNode != null) {
            collect(node.paramNode, parts, i + 1, terminal, wildcardHits);
        }
    }

    /** Split a request path into segments; strips query string and trailing slash. */
    static List<String> splitPath(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new PatternParseException("Request path must start with '/': " + path);
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/")) {
            return List.of();
        }
        String[] p = path.substring(1).split("/");
        List<String> out = new ArrayList<>(p.length);
        for (String s : p) {
            if (s.isEmpty()) {
                throw new PatternParseException("Empty segment in request path: " + path);
            }
            out.add(s);
        }
        return out;
    }

    /**
     * Candidate priority, returns 0 for genuine ties (ambiguous -> conflict).
     * 1) explicit priority desc, 2) path specificity, 3) more header
     * conditions, 4) specific method over ANY.
     */
    static final Comparator<Route> PRIORITY_ORDER = (a, b) -> {
        int c = Integer.compare(a.priority(), b.priority());
        if (c != 0) return -c; // higher explicit priority first
        c = a.pattern().compareSpecificity(b.pattern());
        if (c != 0) return -c; // positive specificity means more specific -> first
        c = Integer.compare(a.headerConditions().size(), b.headerConditions().size());
        if (c != 0) return -c; // more header conditions first
        c = Integer.compare(a.method() == HttpMethod.ANY ? 1 : 0, b.method() == HttpMethod.ANY ? 1 : 0);
        return c; // specific method before ANY
    };

    /** Total order (ties broken deterministically by id) for stable sorting. */
    static final Comparator<Route> FULL_ORDER = PRIORITY_ORDER.thenComparing(Route::id);

    public List<Route> routes() {
        return routes;
    }

    public int size() {
        return routes.size();
    }

    private static final class Node {
        final Map<String, Node> staticChildren = new LinkedHashMap<>();
        final List<RegexBranch> regexNodes = new ArrayList<>();
        Node paramNode;
        final List<Route> routes = new ArrayList<>();
        final List<Route> wildcardRoutes = new ArrayList<>();
    }

    private record RegexBranch(Pattern pattern, Node node) {}
}
