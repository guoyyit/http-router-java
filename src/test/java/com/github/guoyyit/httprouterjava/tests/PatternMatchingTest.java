package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PatternMatchingTest {

    private RouteTable tableOf(Route... routes) {
        return new RouteTable(List.of(routes));
    }

    private Route route(String id, String method, String pattern) {
        return Route.create(id, method, pattern, Map.of(), "handler-" + id, null);
    }

    private String hitHandler(RouteTable t, String m, String path) {
        MatchResult r = t.lookup(HttpMethod.parse(m), path, Map.of());
        if (r instanceof MatchResult.Hit h) {
            return h.route().handlerId();
        }
        throw new AssertionError("Expected HIT, got " + r);
    }

    @Test
    void staticRoutesMatchExactly() {
        RouteTable t = tableOf(route("r1", "GET", "/users/list"));
        assertEquals("handler-r1", hitHandler(t, "GET", "/users/list"));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/users/other", Map.of()));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/users", Map.of()));
    }

    @Test
    void paramSegmentMatchesOneSegment() {
        RouteTable t = tableOf(route("r1", "GET", "/users/{id}"));
        assertEquals("handler-r1", hitHandler(t, "GET", "/users/42"));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/users", Map.of()));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/users/42/posts", Map.of()));
    }

    @Test
    void regexConstraintFiltersParamValues() {
        RouteTable t = tableOf(
                route("num", "GET", "/items/{id:\\d+}"),
                route("str", "GET", "/items/{name:[a-z]+}"));
        assertEquals("handler-num", hitHandler(t, "GET", "/items/123"));
        assertEquals("handler-str", hitHandler(t, "GET", "/items/abc"));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/items/a1", Map.of()));
    }

    @Test
    void wildcardMatchesZeroOrMoreTrailingSegments() {
        RouteTable t = tableOf(route("fs", "GET", "/files/*"));
        assertEquals("handler-fs", hitHandler(t, "GET", "/files"));
        assertEquals("handler-fs", hitHandler(t, "GET", "/files/a"));
        assertEquals("handler-fs", hitHandler(t, "GET", "/files/a/b/c.txt"));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/other", Map.of()));
    }

    @Test
    void optionalSegmentMatchesWithAndWithoutValue() {
        RouteTable t = tableOf(
                route("exact", "GET", "/users"),
                route("opt", "GET", "/users/{id?}"));
        // exact beats optional-absent
        assertEquals("handler-exact", hitHandler(t, "GET", "/users"));
        assertEquals("handler-opt", hitHandler(t, "GET", "/users/7"));
        assertInstanceOf(MatchResult.NoMatch.class, t.lookup(HttpMethod.GET, "/users/7/8", Map.of()));
    }

    @Test
    void staticBeatsParamBeatsWildcardForSameRequest() {
        RouteTable t = tableOf(
                route("w", "GET", "/a/*"),
                route("p", "GET", "/a/{x}"),
                route("s", "GET", "/a/b"));
        assertEquals("handler-s", hitHandler(t, "GET", "/a/b"));
        assertEquals("handler-p", hitHandler(t, "GET", "/a/z"));
        assertEquals("handler-w", hitHandler(t, "GET", "/a/b/c"));
    }

    @Test
    void methodIsPartOfTheMatch() {
        RouteTable t = tableOf(route("r1", "POST", "/items"));
        assertEquals("handler-r1", hitHandler(t, "POST", "/items"));
        MatchResult miss = t.lookup(HttpMethod.GET, "/items", Map.of());
        assertInstanceOf(MatchResult.NoMatch.class, miss);
        assertTrue(((MatchResult.NoMatch) miss).reason().contains("POST"));
    }

    @Test
    void anyMethodMatchesAll() {
        RouteTable t = tableOf(route("r1", "ANY", "/ping"));
        assertEquals("handler-r1", hitHandler(t, "GET", "/ping"));
        assertEquals("handler-r1", hitHandler(t, "DELETE", "/ping"));
    }

    @Test
    void headerConditionsAreEvaluated() {
        Route v1 = Route.create("v1", "GET", "/api", Map.of("X-Version", "1"), "handler-v1", null);
        Route v2 = Route.create("v2", "GET", "/api", Map.of("X-Version", "2"), "handler-v2", null);
        Route def = Route.create("def", "GET", "/api", Map.of(), "handler-def", null);
        RouteTable t = tableOf(v1, v2, def);
        assertEquals("handler-v1",
                ((MatchResult.Hit) t.lookup(HttpMethod.GET, "/api", Map.of("x-version", "1"))).route().handlerId());
        assertEquals("handler-v2",
                ((MatchResult.Hit) t.lookup(HttpMethod.GET, "/api", Map.of("X-Version", "2"))).route().handlerId());
        assertEquals("handler-def",
                ((MatchResult.Hit) t.lookup(HttpMethod.GET, "/api", Map.of("X-Version", "3"))).route().handlerId());
        // no header -> only the unconditional route can match
        assertEquals("handler-def",
                ((MatchResult.Hit) t.lookup(HttpMethod.GET, "/api", Map.of())).route().handlerId());
    }

    @Test
    void negationRegexAndExistsHeaderOperators() {
        Route r1 = Route.create("r1", "GET", "/x",
                Map.of("X-Env", "!prod", "X-Feature", "*"), "h1", null);
        RouteTable t = tableOf(r1);
        assertEquals("h1", ((MatchResult.Hit) t.lookup(HttpMethod.GET, "/x",
                Map.of("x-env", "staging", "x-feature", "on"))).route().handlerId());
        assertInstanceOf(MatchResult.NoMatch.class,
                t.lookup(HttpMethod.GET, "/x", Map.of("x-env", "prod", "x-feature", "on")));
        assertInstanceOf(MatchResult.NoMatch.class,
                t.lookup(HttpMethod.GET, "/x", Map.of("x-env", "staging")));

        Route r2 = Route.create("r2", "GET", "/y", Map.of("X-Ver", "~v\\d+"), "h2", null);
        RouteTable t2 = tableOf(r2);
        assertEquals("h2", ((MatchResult.Hit) t2.lookup(HttpMethod.GET, "/y",
                Map.of("x-ver", "v12"))).route().handlerId());
        assertInstanceOf(MatchResult.NoMatch.class,
                t2.lookup(HttpMethod.GET, "/y", Map.of("x-ver", "beta")));
    }

    @Test
    void combinedPatternAndHeaderMatch() {
        Route r = Route.create("r", "GET", "/orgs/{orgId}/items/{id:\\d+}/*",
                Map.of("X-Env", "!prod"), "hr", null);
        RouteTable t = tableOf(r);
        MatchResult ok = t.lookup(HttpMethod.GET, "/orgs/acme/items/9/meta/detail",
                Map.of("x-env", "dev"));
        assertTrue(ok instanceof MatchResult.Hit);
        assertInstanceOf(MatchResult.NoMatch.class,
                t.lookup(HttpMethod.GET, "/orgs/acme/items/9/meta", Map.of("x-env", "prod")));
        assertInstanceOf(MatchResult.NoMatch.class,
                t.lookup(HttpMethod.GET, "/orgs/acme/items/abc", Map.of("x-env", "dev")));
    }

    @Test
    void invalidPatternsAreRejected() {
        assertThrows(PatternParseException.class, () -> RoutePattern.parse("no-slash"));
        assertThrows(PatternParseException.class, () -> RoutePattern.parse("/a//b"));
        assertThrows(PatternParseException.class, () -> RoutePattern.parse("/a/*/b"));
        assertThrows(PatternParseException.class, () -> RoutePattern.parse("/a/{x?}/b"));
        assertThrows(PatternParseException.class, () -> RoutePattern.parse("/a/{x:[}"));
    }
}
