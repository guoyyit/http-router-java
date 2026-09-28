package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriorityAndConflictTest {

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

    @Test
    void explicitPriorityWinsOverSpecificity() {
        RouteTable t = new RouteTable(List.of(
                r("generic", "GET", "/a/*", 10),
                r("specific", "GET", "/a/b", 0)));
        // generic has higher explicit priority despite lower specificity
        assertEquals("h-generic", handler(t, "GET", "/a/b", Map.of()));
    }

    @Test
    void specificityWinsWhenExplicitPriorityEqual() {
        RouteTable t = new RouteTable(List.of(
                r("s", "GET", "/a/b", 0),
                r("p", "GET", "/a/{x}", 0),
                r("w", "GET", "/a/*", 0)));
        assertEquals("h-s", handler(t, "GET", "/a/b", Map.of()));
        assertEquals("h-p", handler(t, "GET", "/a/z", Map.of()));
        assertEquals("h-w", handler(t, "GET", "/a/b/c", Map.of()));
    }

    @Test
    void regexBeatsPlainParam() {
        RouteTable t = new RouteTable(List.of(
                r("num", "GET", "/v/{n:\\d+}", 0),
                r("any", "GET", "/v/{x}", 0)));
        assertEquals("h-num", handler(t, "GET", "/v/42", Map.of()));
        assertEquals("h-any", handler(t, "GET", "/v/ab", Map.of()));
    }

    @Test
    void firstDifferentSegmentDecides() {
        // /a/{x} vs /{y}/b both match /a/b; segment 0 differs -> deterministic, no ambiguity
        RouteTable t = new RouteTable(List.of(
                r("left", "GET", "/a/{x}", 0),
                r("right", "GET", "/{y}/b", 0)));
        assertEquals("h-left", handler(t, "GET", "/a/b", Map.of()));
    }

    @Test
    void moreHeaderConditionsWinWhenBothMatch() {
        Route narrow = r("narrow", "GET", "/api",
                Map.of("X-Ver", "1", "X-Env", "!prod"));
        Route broad = r("broad", "GET", "/api", Map.of("X-Ver", "1"));
        RouteTable t = new RouteTable(List.of(broad, narrow));
        assertEquals("h-narrow", handler(t, "GET", "/api", Map.of("x-ver", "1", "x-env", "dev")));
        assertEquals("h-broad", handler(t, "GET", "/api", Map.of("x-ver", "1", "x-env", "prod")));
    }

    @Test
    void methodSpecificBeatsAny() {
        RouteTable t = new RouteTable(List.of(
                r("a", "ANY", "/x", 0),
                r("g", "GET", "/x", 0)));
        assertEquals("h-g", handler(t, "GET", "/x", Map.of()));
        assertEquals("h-a", handler(t, "POST", "/x", Map.of()));
    }

    @Test
    void exactMatchBeatsOptional() {
        RouteTable t = new RouteTable(List.of(
                r("exact", "GET", "/u", 0),
                r("opt", "GET", "/u/{id?}", 0)));
        assertEquals("h-exact", handler(t, "GET", "/u", Map.of()));
        assertEquals("h-opt", handler(t, "GET", "/u/9", Map.of()));
    }

    @Test
    void trulyTiedCandidatesReturnConflictNotArbitraryChoice() {
        // bypass builder (which would reject) to exercise the runtime safety net
        RouteTable t = new RouteTable(List.of(
                r("one", "GET", "/a/{x}", 0),
                r("two", "GET", "/a/{y}", 0)));
        MatchResult res = t.lookup(HttpMethod.GET, "/a/b", Map.of());
        assertInstanceOf(MatchResult.Conflict.class, res);
        List<String> ids = ((MatchResult.Conflict) res).candidates().stream().map(Route::id).toList();
        assertTrue(ids.contains("one") && ids.contains("two"));
    }

    // ---- registration-time rejection ----

    @Test
    void duplicateRouteKeyIsRejected() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/u/{id}", 0)));
        RouteConflictException ex = assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("b", "GET", "/u/{id}", 0)).build());
        assertTrue(ex.getMessage().contains("Duplicate"));
        // original table retained
        assertEquals(1, base.size());
    }

    @Test
    void duplicateIdIsRejected() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/u/{id}", 0)));
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("a", "GET", "/other/{id}", 0)).build());
        assertEquals(1, base.size());
    }

    @Test
    void overlappingEqualSpecificityIsRejectedButDifferentPriorityIsAllowed() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/u/{id}", 0)));
        // same specificity, same priority -> rejected
        assertThrows(RouteConflictException.class,
                () -> new RouteTableBuilder(base).add(r("b", "GET", "/u/{name}", 0)).build());
        // explicit priority disambiguates -> allowed
        RouteTable next = new RouteTableBuilder(base).add(r("b", "GET", "/u/{name}", 5)).build();
        assertEquals(2, next.size());
        assertEquals("h-b", handler(next, "GET", "/u/9", Map.of()));
    }

    @Test
    void contradictoryHeadersDoNotConflict() {
        RouteTable base = new RouteTable(List.of(
                r("a", "GET", "/api", Map.of("X-Ver", "1"))));
        RouteTable next = new RouteTableBuilder(base)
                .add(r("b", "GET", "/api", Map.of("X-Ver", "2")))
                .build();
        assertEquals(2, next.size());
        assertEquals("h-a", handler(next, "GET", "/api", Map.of("x-ver", "1")));
        assertEquals("h-b", handler(next, "GET", "/api", Map.of("x-ver", "2")));
    }

    @Test
    void incompatibleStaticPrefixesAreAllowed() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/users/{id}", 0)));
        RouteTable next = new RouteTableBuilder(base).add(r("b", "GET", "/users/list", 0)).build();
        assertEquals(2, next.size());
        assertEquals("h-b", handler(next, "GET", "/users/list", Map.of()));
        assertEquals("h-a", handler(next, "GET", "/users/42", Map.of()));
    }

    @Test
    void methodsThatCannotOverlapAreAllowed() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/x", 0)));
        RouteTable next = new RouteTableBuilder(base).add(r("b", "POST", "/x", 0)).build();
        assertEquals(2, next.size());
    }

    @Test
    void deletingUnknownRouteIsRejectedAndTableRetained() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/x", 0)));
        assertThrows(RouteNotFoundException.class,
                () -> new RouteTableBuilder(base).remove("missing").build());
        assertEquals(1, base.size());
    }

    @Test
    void oneConflictRejectsTheWholeBatch() {
        RouteTable base = new RouteTable(List.of(r("a", "GET", "/x", 0)));
        RouteTableBuilder builder = new RouteTableBuilder(base)
                .add(r("ok", "GET", "/y", 0))
                .add(r("dup", "GET", "/x", 0)); // conflict
        assertThrows(RouteConflictException.class, builder::build);
        assertEquals(1, base.size());
    }

    @Test
    void trieValidatorCatchesFarAndNestedConflicts() {
        // many non-overlapping routes must not hide a conflict deep in a static subtree
        List<Route> routes = new java.util.ArrayList<>();
        routes.add(r("base", "GET", "/shops/1/items/{id}", 0));
        for (int i = 100; i < 130; i++) {
            routes.add(r("filler" + i, "GET", "/shops/1/cat/" + i, 0));
        }
        // same specificity overlap with base, hidden behind the filler subtree
        routes.add(r("evil", "GET", "/shops/1/items/{name}", 0));
        assertThrows(RouteConflictException.class, () -> RouteTable.buildValidated(routes));

        // regex vs a static value it cannot match -> allowed
        List<Route> ok = new java.util.ArrayList<>(java.util.List.of(
                r("num", "GET", "/v/{n:\\d+}", 0),
                r("staticAlpha", "GET", "/v/abc", 0)));
        assertEquals(2, RouteTable.buildValidated(ok).size());

        // wildcard vs static leaf with same priority -> ambiguous -> rejected
        List<Route> wild = new java.util.ArrayList<>(java.util.List.of(
                r("deep", "GET", "/fs/a/b/c", 0),
                r("star", "GET", "/fs/*", 0)));
        // static leaf is strictly more specific -> allowed, and it wins at lookup
        assertEquals(2, RouteTable.buildValidated(wild).size());
        RouteTable t = RouteTable.buildValidated(wild);
        assertEquals("h-deep", handler(t, "GET", "/fs/a/b/c", Map.of()));
        assertEquals("h-star", handler(t, "GET", "/fs/a/b/x", Map.of()));

        // equal-rank two-params tie is rejected even when collected via a wider subtree
        List<Route> trueTie = new java.util.ArrayList<>(java.util.List.of(
                r("p1", "GET", "/z/{x}", 0),
                r("p2", "GET", "/z/{y}", 0)));
        assertThrows(RouteConflictException.class, () -> RouteTable.buildValidated(trueTie));
    }
}
