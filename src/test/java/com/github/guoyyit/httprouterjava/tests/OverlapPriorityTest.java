package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Overlap boundary combinations: lookup priority and registration acceptance
 * must agree on the same winner for every common request.
 */
class OverlapPriorityTest {

    private Route r(String id, String method, String pattern, Integer priority) {
        return Route.create(id, method, pattern, Map.of(), "h-" + id, priority);
    }

    private Route r(String id, String method, String pattern, Map<String, String> headers) {
        return Route.create(id, method, pattern, headers, "h-" + id, null);
    }

    private String handler(RouteTable t, String m, String path, Map<String, String> h) {
        MatchResult res = t.lookup(HttpMethod.parse(m), path, h);
        if (res instanceof MatchResult.Hit hit) {
            return hit.route().handlerId();
        }
        throw new AssertionError("Expected HIT, got " + res);
    }

    /**
     * The exact-vs-wildcard reproduction: /files and /files/* coexist, and a
     * request for /files must hit the exact rule, not the wildcard rule.
     */
    @Test
    void exactPathBeatsWildcardTail() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files", 0),
                r("wild", "GET", "/files/*", 0)));
        assertEquals("h-exact", handler(t, "GET", "/files", Map.of()));
        assertEquals("h-wild", handler(t, "GET", "/files/a", Map.of()));
        assertEquals("h-wild", handler(t, "GET", "/files/a/b/c", Map.of()));
    }

    @Test
    void exactPathBeatsOptionalTail() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files", 0),
                r("opt", "GET", "/files/{id?}", 0)));
        assertEquals("h-exact", handler(t, "GET", "/files", Map.of()));
        assertEquals("h-opt", handler(t, "GET", "/files/9", Map.of()));
    }

    @Test
    void regexBeatsParamOnMatchingValues() {
        RouteTable t = new RouteTable(List.of(
                r("num", "GET", "/v/{n:\\d+}", 0),
                r("any", "GET", "/v/{x}", 0)));
        assertEquals("h-num", handler(t, "GET", "/v/42", Map.of()));
        assertEquals("h-any", handler(t, "GET", "/v/ab", Map.of()));
    }

    @Test
    void paramBeatsWildcardTail() {
        RouteTable t = new RouteTable(List.of(
                r("param", "GET", "/a/{x}", 0),
                r("wild", "GET", "/a/*", 0)));
        assertEquals("h-param", handler(t, "GET", "/a/b", Map.of()));
        assertEquals("h-wild", handler(t, "GET", "/a/b/c", Map.of()));
    }

    @Test
    void trailingSlashVariantHitsExactRule() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files", 0),
                r("wild", "GET", "/files/*", 0)));
        // trailing slash is normalized away; both /files and /files/ hit the exact rule
        assertEquals("h-exact", handler(t, "GET", "/files/", Map.of()));
        assertEquals("h-exact", handler(t, "GET", "/files", Map.of()));
    }

    @Test
    void staticBeatsRegexBeatsParamBeatsWildcard() {
        RouteTable t = new RouteTable(List.of(
                r("wild", "GET", "/s/*", 0),
                r("param", "GET", "/s/{x}", 0),
                r("regex", "GET", "/s/{n:\\d+}", 0),
                r("static", "GET", "/s/list", 0)));
        assertEquals("h-static", handler(t, "GET", "/s/list", Map.of()));
        assertEquals("h-regex", handler(t, "GET", "/s/42", Map.of()));
        assertEquals("h-param", handler(t, "GET", "/s/ab", Map.of()));
        assertEquals("h-wild", handler(t, "GET", "/s/ab/c", Map.of()));
    }

    @Test
    void firstDifferentSegmentDecidesOverlap() {
        // /a/{x} vs /{y}/b both match /a/b; segment 0 differs -> deterministic winner
        RouteTable t = new RouteTable(List.of(
                r("left", "GET", "/a/{x}", 0),
                r("right", "GET", "/{y}/b", 0)));
        assertEquals("h-left", handler(t, "GET", "/a/b", Map.of()));
    }

    // ---- registration phase: same winner semantics as lookup ----

    @Test
    void exactAndWildcardOverlapIsAllowedAndHitsExact() {
        RouteTable base = new RouteTable(List.of(r("exact", "GET", "/files", 0)));
        // priority disambiguates the overlap -> allowed
        RouteTable next = new RouteTableBuilder(base)
                .add(r("wild", "GET", "/files/*", 0))
                .build();
        assertEquals(2, next.size());
        assertEquals("h-exact", handler(next, "GET", "/files", Map.of()));
        assertEquals("h-wild", handler(next, "GET", "/files/a", Map.of()));
    }

    @Test
    void exactAndOptionalOverlapIsAllowedAndHitsExact() {
        RouteTable base = new RouteTable(List.of(r("exact", "GET", "/files", 0)));
        RouteTable next = new RouteTableBuilder(base)
                .add(r("opt", "GET", "/files/{id?}", 0))
                .build();
        assertEquals(2, next.size());
        assertEquals("h-exact", handler(next, "GET", "/files", Map.of()));
        assertEquals("h-opt", handler(next, "GET", "/files/9", Map.of()));
    }

    @Test
    void paramAndWildcardOverlapIsAllowedAndHitsParam() {
        RouteTable base = new RouteTable(List.of(r("param", "GET", "/a/{x}", 0)));
        RouteTable next = new RouteTableBuilder(base)
                .add(r("wild", "GET", "/a/*", 0))
                .build();
        assertEquals(2, next.size());
        assertEquals("h-param", handler(next, "GET", "/a/b", Map.of()));
        assertEquals("h-wild", handler(next, "GET", "/a/b/c", Map.of()));
    }

    @Test
    void regexAndParamOverlapIsAllowedAndHitsRegex() {
        RouteTable base = new RouteTable(List.of(r("num", "GET", "/v/{n:\\d+}", 0)));
        RouteTable next = new RouteTableBuilder(base)
                .add(r("any", "GET", "/v/{x}", 0))
                .build();
        assertEquals(2, next.size());
        assertEquals("h-num", handler(next, "GET", "/v/42", Map.of()));
        assertEquals("h-any", handler(next, "GET", "/v/ab", Map.of()));
    }

    @Test
    void equalSpecificityOverlapIsRejected() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/a/{x}", 0)));
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("b", "GET", "/a/{y}", 0)).build());
        assertEquals(1, base.size());
    }

    @Test
    void equalSpecificityWildcardOverlapIsRejected() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/a/*", 0)));
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("b", "GET", "/a/*", 0)).build());
        assertEquals(1, base.size());
    }

    @Test
    void differentPriorityOverlapIsAllowedAndHigherPriorityWins() {
        RouteTable base = new RouteTable(List.of(r("exact", "GET", "/files", 0)));
        RouteTable next = new RouteTableBuilder(base)
                .add(r("wild", "GET", "/files/*", 10))
                .build();
        assertEquals(2, next.size());
        // explicit priority overrides path specificity
        assertEquals("h-wild", handler(next, "GET", "/files", Map.of()));
    }

    @Test
    void contradictoryHeadersStillAllowOverlap() {
        RouteTable base = new RouteTable(List.of(
                r("exact", "GET", "/files", Map.of("X-Ver", "1"))));
        RouteTable next = new RouteTableBuilder(base)
                .add(r("wild", "GET", "/files/*", Map.of("X-Ver", "2")))
                .build();
        assertEquals(2, next.size());
        assertEquals("h-exact", handler(next, "GET", "/files", Map.of("x-ver", "1")));
        assertEquals("h-wild", handler(next, "GET", "/files", Map.of("x-ver", "2")));
    }

    @Test
    void deletingOneRuleDoesNotChangeOtherHitResults() {
        // exact + wildcard: each wins on its own request shape
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files", 0),
                r("wild", "GET", "/files/*", 0)));
        assertEquals("h-exact", handler(t, "GET", "/files", Map.of()));
        assertEquals("h-wild", handler(t, "GET", "/files/a", Map.of()));

        // deleting the wildcard does not change the exact rule's hit on /files
        RouteTable withoutWild = new RouteTableBuilder(t).remove("wild").build();
        assertEquals("h-exact", handler(withoutWild, "GET", "/files", Map.of()));

        // deleting the exact rule does not change the wildcard's hit on /files/a
        RouteTable withoutExact = new RouteTableBuilder(t).remove("exact").build();
        assertEquals("h-wild", handler(withoutExact, "GET", "/files/a", Map.of()));
    }
}
