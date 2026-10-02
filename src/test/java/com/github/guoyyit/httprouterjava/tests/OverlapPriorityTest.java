package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduction and regression tests for overlapping-rule priority:
 * an exact static rule must beat a covering wildcard/optional rule,
 * and registration-time decisions must agree with lookup-time decisions.
 */
class OverlapPriorityTest {

    private Route r(String id, String method, String pattern) {
        return Route.create(id, method, pattern, Map.of(), "h-" + id, null);
    }

    private String handler(RouteTable t, String m, String path) {
        MatchResult res = t.lookup(HttpMethod.parse(m), path, Map.of());
        if (res instanceof MatchResult.Hit hit) {
            return hit.route().handlerId();
        }
        throw new AssertionError("Expected HIT, got " + res);
    }

    // ---- lookup-time priority on overlapping rules ----

    @Test
    void exactPathBeatsWildcardTail() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files"),
                r("wild", "GET", "/files/*")));
        assertEquals("h-exact", handler(t, "GET", "/files"));
        assertEquals("h-wild", handler(t, "GET", "/files/a"));
        assertEquals("h-wild", handler(t, "GET", "/files/a/b"));
    }

    @Test
    void exactPathBeatsOptionalTail() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files"),
                r("opt", "GET", "/files/{id?}")));
        assertEquals("h-exact", handler(t, "GET", "/files"));
        assertEquals("h-opt", handler(t, "GET", "/files/9"));
    }

    @Test
    void trailingSlashStillHitsExactNotWildcard() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/files"),
                r("wild", "GET", "/files/*")));
        // trailing slash is normalized away -> same winner as "/files"
        assertEquals("h-exact", handler(t, "GET", "/files/"));
        assertEquals("h-wild", handler(t, "GET", "/files/a/"));
    }

    @Test
    void regexBeatsParamOnOverlap() {
        RouteTable t = new RouteTable(List.of(
                r("num", "GET", "/v/{n:\\d+}"),
                r("any", "GET", "/v/{x}")));
        assertEquals("h-num", handler(t, "GET", "/v/42"));
        assertEquals("h-any", handler(t, "GET", "/v/ab"));
    }

    @Test
    void staticBeatsRegexOnOverlap() {
        RouteTable t = new RouteTable(List.of(
                r("word", "GET", "/items/{id:[a-z]+}"),
                r("list", "GET", "/items/list")));
        assertEquals("h-list", handler(t, "GET", "/items/list"));
        assertEquals("h-word", handler(t, "GET", "/items/other"));
    }

    @Test
    void paramBeatsWildcardOnOverlap() {
        RouteTable t = new RouteTable(List.of(
                r("param", "GET", "/a/{x}"),
                r("wild", "GET", "/a/*")));
        assertEquals("h-param", handler(t, "GET", "/a/b"));
        assertEquals("h-wild", handler(t, "GET", "/a/b/c"));
        assertEquals("h-wild", handler(t, "GET", "/a"));
    }

    @Test
    void optionalBeatsWildcardOnOverlap() {
        RouteTable t = new RouteTable(List.of(
                r("opt", "GET", "/a/{x?}"),
                r("wild", "GET", "/a/*")));
        assertEquals("h-opt", handler(t, "GET", "/a"));
        assertEquals("h-opt", handler(t, "GET", "/a/b"));
        assertEquals("h-wild", handler(t, "GET", "/a/b/c"));
    }

    @Test
    void shorterPrefixRuleBeatsLongerRuleWithWildcardTail() {
        // /p/{x} is a rank-prefix of /p/{x}/*; both match /p/b -> shorter exact rule wins
        RouteTable t = new RouteTable(List.of(
                r("short", "GET", "/p/{x}"),
                r("long", "GET", "/p/{x}/*")));
        assertEquals("h-short", handler(t, "GET", "/p/b"));
        assertEquals("h-long", handler(t, "GET", "/p/b/c"));
    }

    @Test
    void rootExactBeatsRootWildcard() {
        RouteTable t = new RouteTable(List.of(
                r("root", "GET", "/"),
                r("wild", "GET", "/*")));
        assertEquals("h-root", handler(t, "GET", "/"));
        assertEquals("h-wild", handler(t, "GET", "/anything"));
    }

    // ---- registration-time decisions agree with lookup-time winners ----

    @Test
    void exactAndWildcardCoexistAndCommonRequestsHitExact() {
        RouteTable base = new RouteTable(List.of(r("exact", "GET", "/files")));
        RouteTable next = new RouteTableBuilder(base).add(r("wild", "GET", "/files/*")).build();
        assertEquals(2, next.size());
        assertEquals("h-exact", handler(next, "GET", "/files"));
        // reverse registration order yields the same winner
        RouteTable base2 = new RouteTable(List.of(r("wild", "GET", "/files/*")));
        RouteTable next2 = new RouteTableBuilder(base2).add(r("exact", "GET", "/files")).build();
        assertEquals("h-exact", handler(next2, "GET", "/files"));
    }

    @Test
    void exactAndOptionalCoexistAndCommonRequestsHitExact() {
        RouteTable base = new RouteTable(List.of(r("opt", "GET", "/files/{id?}")));
        RouteTable next = new RouteTableBuilder(base).add(r("exact", "GET", "/files")).build();
        assertEquals(2, next.size());
        assertEquals("h-exact", handler(next, "GET", "/files"));
        assertEquals("h-opt", handler(next, "GET", "/files/9"));
    }

    @Test
    void regexAndParamCoexistAndCommonRequestsHitRegex() {
        RouteTable base = new RouteTable(List.of(r("any", "GET", "/v/{x}")));
        RouteTable next = new RouteTableBuilder(base).add(r("num", "GET", "/v/{n:\\d+}")).build();
        assertEquals(2, next.size());
        assertEquals("h-num", handler(next, "GET", "/v/42"));
        assertEquals("h-any", handler(next, "GET", "/v/ab"));
    }

    @Test
    void paramAndWildcardCoexistAndCommonRequestsHitParam() {
        RouteTable base = new RouteTable(List.of(r("wild", "GET", "/a/*")));
        RouteTable next = new RouteTableBuilder(base).add(r("param", "GET", "/a/{x}")).build();
        assertEquals(2, next.size());
        assertEquals("h-param", handler(next, "GET", "/a/b"));
        assertEquals("h-wild", handler(next, "GET", "/a/b/c"));
    }

    @Test
    void equalRankOverlappingPairsAreRejectedAtRegistration() {
        // param vs param with different names: no unique winner -> rejected
        RouteTable base = new RouteTable(List.of(r("p1", "GET", "/a/{x}")));
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("p2", "GET", "/a/{y}")).build());
        assertEquals(1, base.size());
        // optional vs optional with different names: same conclusion
        RouteTable base2 = new RouteTable(List.of(r("o1", "GET", "/a/{x?}")));
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base2).add(r("o2", "GET", "/a/{y?}")).build());
        assertEquals(1, base2.size());
    }

    @Test
    void deletingOneRuleDoesNotChangeRemainingHits() {
        Route exact = r("exact", "GET", "/a/b");
        Route param = r("param", "GET", "/a/{x}");
        Route wild = r("wild", "GET", "/a/*");
        RouteTable full = RouteTable.buildValidated(List.of(exact, param, wild));
        assertEquals("h-exact", handler(full, "GET", "/a/b"));
        assertEquals("h-param", handler(full, "GET", "/a/c"));
        assertEquals("h-wild", handler(full, "GET", "/a/b/c"));

        // remove the param rule: exact and wildcard keep their previous outcomes
        RouteTable withoutParam = RouteTable.buildValidated(List.of(exact, wild));
        assertEquals("h-exact", handler(withoutParam, "GET", "/a/b"));
        assertEquals("h-wild", handler(withoutParam, "GET", "/a/b/c"));

        // remove the exact rule: param and wildcard keep their previous outcomes
        RouteTable withoutExact = RouteTable.buildValidated(List.of(param, wild));
        assertEquals("h-param", handler(withoutExact, "GET", "/a/b"));
        assertEquals("h-param", handler(withoutExact, "GET", "/a/c"));
        assertEquals("h-wild", handler(withoutExact, "GET", "/a/b/c"));
    }

    @Test
    void rejectedRegistrationLeavesOriginalTableUsable() {
        RouteTable base = RouteTable.buildValidated(List.of(
                r("exact", "GET", "/files"),
                r("wild", "GET", "/files/*")));
        // duplicate of the wildcard pattern under a new id must be rejected, table untouched
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("wild2", "GET", "/files/*")).build());
        assertEquals(2, base.size());
        assertEquals("h-exact", handler(base, "GET", "/files"));
        assertEquals("h-wild", handler(base, "GET", "/files/a"));

        // a genuinely ambiguous pair (equal-rank params) is rejected as well
        RouteTable paramBase = new RouteTable(List.of(r("p1", "GET", "/files/{x}")));
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(paramBase).add(r("p2", "GET", "/files/{y}")).build());
        assertEquals(1, paramBase.size());
        assertEquals("h-p1", handler(paramBase, "GET", "/files/a"));
    }
}
