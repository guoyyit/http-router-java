package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.pattern.CatchAllSegment;
import com.github.guoyyit.httprouterjava.pattern.LiteralSegment;
import com.github.guoyyit.httprouterjava.pattern.ParamSegment;
import com.github.guoyyit.httprouterjava.pattern.PathPattern;
import com.github.guoyyit.httprouterjava.pattern.Segment;
import com.github.guoyyit.httprouterjava.pattern.WildcardSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registration-time overlap / ambiguity detector.
 *
 * <p>Two equal-priority, equal-method routes are only a <em>conflict</em> when
 * there exists a concrete request that fully matches both with an identical
 * rank &mdash; i.e. the matched path alternatives have equal specificity
 * score <em>and</em> the routes declare the same number of header conditions
 * (the third tie-break) while those conditions are mutually compatible.
 * Patterns that merely overlap but are always disambiguated by specificity
 * (e.g. {@code /users/me} vs {@code /users/{id}}, or a deeper exact route vs
 * an ancestor {@code **}) are allowed to coexist; their relative order is
 * stable and is resolved at match time by {@link RouteTable}.</p>
 *
 * <p>Detection uses a structural trie per (method, priority) group. A node
 * stores the routes terminating there with an exact-length alternative and
 * those terminating there with a catch-all, each tagged with the alternative's
 * specificity score. Insertion is a frontier expansion over every child node
 * structurally compatible with each segment (canonical literal child plus
 * parameter/wildcard children; parameter/wildcard inserts also visit every
 * literal child). At every entered node the new alternative is compared
 * against the stored cells; a conflict is reported only when the scores tie
 * (and header counts tie and header conditions are compatible). A catch-all
 * alternative additionally compares against every terminal cell in the subtree
 * below its prefix. Disjoint routes never share a frontier node, so validation
 * stays close to linear in the number of pattern segments times the small
 * per-level branching.</p>
 */
final class OverlapIndex {

    private final Map<GroupKey, Node> roots = new HashMap<>();

    void add(PathPattern pattern, Route route) {
        GroupKey key = new GroupKey(route.getMethod(), route.getPriority());
        Node root = roots.computeIfAbsent(key, k -> new Node());
        for (List<Segment> alternative : pattern.getAlternatives()) {
            insert(root, alternative, route);
        }
    }

    private static void insert(Node root, List<Segment> pattern, Route route) {
        int fullScore = score(pattern);
        // Frontier maps a visited node to the specificity score accumulated by
        // the fixed segments traversed to reach it. For an exact-length route
        // that equals the full score; for a catch-all route it is the score on
        // the shared concrete path ending at that node.
        Map<Node, Integer> frontier = new LinkedHashMap<>();
        frontier.put(root, 0);
        Set<Node> canonicalNodes = new LinkedHashSet<>();
        canonicalNodes.add(root);

        int depth = 0;
        while (depth < pattern.size()
                && !(pattern.get(depth) instanceof CatchAllSegment)) {
            Segment segment = pattern.get(depth);
            int weight = weight(segment);
            Map<Node, Integer> nextFrontier = new LinkedHashMap<>();
            Set<Node> nextCanonical = new LinkedHashSet<>();
            for (Map.Entry<Node, Integer> e : frontier.entrySet()) {
                Node node = e.getKey();
                int nodeScore = e.getValue();
                boolean wasCanonical = canonicalNodes.contains(node);

                // A catch-all registered at this prefix covers deeper paths.
                // On the canonical descent the new route ends with its full
                // specificity; on a cross-branch visit it ends at this node's
                // accumulated score. Compare against whichever applies.
                int scoreOnSharedPath = wasCanonical ? fullScore : nodeScore;
                check(node.catchAll, route, scoreOnSharedPath);

                for (Node child : compatibleChildren(node, segment)) {
                    nextFrontier.merge(child, nodeScore + weight, Math::max);
                }
                if (wasCanonical) {
                    Node canonical = canonicalChild(node, segment);
                    nextFrontier.merge(canonical, nodeScore + weight, Math::max);
                    nextCanonical.add(canonical);
                }
            }
            frontier = nextFrontier;
            canonicalNodes = nextCanonical;
            depth++;
        }

        boolean catchAll = depth < pattern.size();
        for (Map.Entry<Node, Integer> e : frontier.entrySet()) {
            Node node = e.getKey();
            int matchedScore = e.getValue();
            if (catchAll) {
                check(node.catchAll, route, matchedScore);
                check(node.exact, route, matchedScore);
                // Existing terminals strictly below the canonical prefix.
                // Only the canonical node owns the subtree; cross-branch
                // siblings reached via param/wildcard are not the catch-all's
                // prefix and must not be scanned.
                if (canonicalNodes.contains(node)) {
                    checkLiteralSubtree(node, route, matchedScore);
                    node.catchAll.add(new Cell(route, matchedScore));
                }
            } else {
                if (matchedScore != fullScore) {
                    throw new IllegalStateException("scoring bookkeeping error");
                }
                check(node.exact, route, matchedScore);
                check(node.catchAll, route, matchedScore);
                if (canonicalNodes.contains(node)) {
                    node.exact.add(new Cell(route, matchedScore));
                }
            }
        }
    }

    /**
     * Conflict check against stored terminal cells. Equal specificity score
     * AND equal number of header conditions AND compatible header conditions
     * means some concrete request yields an exact rank tie.
     */
    private static void check(List<Cell> cells, Route route, int score) {
        for (Cell cell : cells) {
            if (cell.route == route) {
                continue;
            }
            if (cell.score == score
                    && sameHeaderCount(cell.route, route)
                    && RouteValidator.headersCompatible(
                            cell.route.getHeaders(), route.getHeaders())) {
                throw conflict(cell.route, route);
            }
        }
    }

    private static boolean sameHeaderCount(Route a, Route b) {
        return a.getHeaders().size() == b.getHeaders().size();
    }

    /**
     * Existing children structurally compatible with {@code segment}, other
     * than the same-value literal child (that is the canonical child and is
     * handled by the caller).
     */
    private static List<Node> compatibleChildren(Node node, Segment segment) {
        List<Node> out = new ArrayList<>();
        if (!(segment instanceof LiteralSegment)) {
            out.addAll(node.literals.values());
        }
        if (node.param != null) {
            out.add(node.param);
        }
        if (node.wildcard != null) {
            out.add(node.wildcard);
        }
        return out;
    }

    private static Node canonicalChild(Node node, Segment segment) {
        int w = weight(segment);
        if (segment instanceof LiteralSegment l) {
            return node.literals.computeIfAbsent(l.value(), k -> new Node());
        }
        if (segment instanceof ParamSegment) {
            if (node.param == null) {
                node.param = new Node();
            }
            return node.param;
        }
        if (segment instanceof WildcardSegment) {
            if (node.wildcard == null) {
                node.wildcard = new Node();
            }
            return node.wildcard;
        }
        throw new IllegalStateException("unexpected segment: " + segment);
    }

    /**
     * Scan the literal subtree below a newly inserted catch-all's canonical
     * prefix for ambiguous terminals.
     *
     * <p>A catch-all accepts any concrete path, so literal descendants cover
     * the concrete paths that can terminate below. Param/wildcard descendants
     * are reached through the normal frontier expansion and checked there.</p>
     *
     * @param prefixFixedScore the new catch-all's fixed specificity
     */
    private static void checkLiteralSubtree(Node root, Route route,
                                            int prefixFixedScore) {
        for (Node node : root.literals.values()) {
            for (Cell exact : node.exact) {
                if (exact.score == prefixFixedScore
                        && sameHeaderCount(exact.route, route)
                        && RouteValidator.headersCompatible(
                                exact.route.getHeaders(), route.getHeaders())) {
                    throw conflict(exact.route, route);
                }
            }
            for (Cell otherCatchAll : node.catchAll) {
                if (otherCatchAll.score == prefixFixedScore
                        && sameHeaderCount(otherCatchAll.route, route)
                        && RouteValidator.headersCompatible(
                                otherCatchAll.route.getHeaders(), route.getHeaders())) {
                    throw conflict(otherCatchAll.route, route);
                }
            }
            checkLiteralSubtree(node, route, prefixFixedScore);
        }
    }

    private static int weight(Segment s) {
        if (s instanceof LiteralSegment) {
            return 3;
        }
        if (s instanceof ParamSegment) {
            return 2;
        }
        if (s instanceof WildcardSegment) {
            return 1;
        }
        return 0; // catch-all
    }

    /** Same weights as the runtime ranking in RouteTable. */
    private static int score(List<Segment> alternative) {
        int score = 0;
        for (Segment s : alternative) {
            if (s instanceof LiteralSegment) {
                score += 3;
            } else if (s instanceof ParamSegment) {
                score += 2;
            } else if (s instanceof CatchAllSegment) {
                // catch-all contributes nothing; fixed prefix length decides
            } else if (s instanceof WildcardSegment) {
                score += 1;
            }
        }
        return score;
    }

    private static ConflictException conflict(Route a, Route b) {
        return new ConflictException(
                "routes '" + a.getHandlerId() + "' and '" + b.getHandlerId()
                        + "' match some request with an identical rank at equal"
                        + " priority " + a.getPriority() + "; give one a"
                        + " different priority, a more specific path, or mutually"
                        + " exclusive header conditions",
                List.of(a.getHandlerId(), b.getHandlerId()));
    }

    private record GroupKey(HttpMethod method, int priority) {
    }

    private record Cell(Route route, int score) {
    }

    private static final class Node {
        private final Map<String, Node> literals = new HashMap<>();
        private Node param;
        private Node wildcard;
        private final List<Cell> exact = new ArrayList<>();
        private final List<Cell> catchAll = new ArrayList<>();
    }
}
