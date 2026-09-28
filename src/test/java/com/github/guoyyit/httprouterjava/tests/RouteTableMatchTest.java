package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.CompiledRoute;
import com.github.guoyyit.httprouterjava.core.ConflictException;
import com.github.guoyyit.httprouterjava.core.MethodNotAllowedException;
import com.github.guoyyit.httprouterjava.core.NoMatchException;
import com.github.guoyyit.httprouterjava.core.RouteTable;
import com.github.guoyyit.httprouterjava.model.HeaderCondition;
import com.github.guoyyit.httprouterjava.model.HeaderMatchType;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTableMatchTest {

    private final PathPatternParser parser = new PathPatternParser();

    private RouteTable table(Route... routes) {
        List<CompiledRoute> compiled = new ArrayList<>();
        for (Route r : routes) {
            compiled.add(new CompiledRoute(r, parser.parse(r.getPath())));
        }
        return new RouteTable(1, compiled);
    }

    private Route route(String id, HttpMethod m, String path, int priority,
                        HeaderCondition... headers) {
        return new Route(id, m, path, List.of(headers), priority);
    }

    @Test
    void staticRouteWinsOverParamAtEqualPriority() {
        RouteTable t = table(
                route("users-list", HttpMethod.GET, "/users", 0),
                route("user-by-id", HttpMethod.GET, "/users/{id}", 0),
                route("static-me", HttpMethod.GET, "/users/me", 0));
        assertEquals("static-me", t.find(HttpMethod.GET, "/users/me", Map.of()).handlerId());
        assertEquals("user-by-id",
                t.find(HttpMethod.GET, "/users/42", Map.of()).handlerId());
    }

    @Test
    void extractsPathVariablesAndCatchAll() {
        RouteTable t = table(
                route("files", HttpMethod.GET, "/files/**:rest", 0),
                route("user", HttpMethod.GET, "/users/{id}/posts/{postId}", 0));
        MatchResult m = t.find(HttpMethod.GET, "/users/7/posts/abc", Map.of());
        assertEquals("user", m.handlerId());
        assertEquals(Map.of("id", "7", "postId", "abc"), m.pathVariables());

        MatchResult f = t.find(HttpMethod.GET, "/files/a/b/c", Map.of());
        assertEquals(Map.of("rest", "a/b/c"), f.pathVariables());
    }

    @Test
    void higherExplicitPriorityWinsEvenWhenLessSpecific() {
        RouteTable t = table(
                route("specific", HttpMethod.GET, "/users/{id}", 0),
                route("override", HttpMethod.GET, "/users/{id}", 10));
        assertEquals("override",
                t.find(HttpMethod.GET, "/users/42", Map.of()).handlerId());
    }

    @Test
    void regexConstraintFiltersAndFallsThrough() {
        RouteTable t = table(
                route("numeric", HttpMethod.GET, "/users/{id:[0-9]+}", 0),
                route("byname", HttpMethod.GET, "/users/{name:[a-z]+}", 0));
        assertEquals("numeric", t.find(HttpMethod.GET, "/users/42", Map.of()).handlerId());
        assertEquals("byname", t.find(HttpMethod.GET, "/users/alice", Map.of()).handlerId());
    }

    @Test
    void optionalFragmentMatchesBothShapes() {
        RouteTable t = table(
                route("api", HttpMethod.GET, "/api(/v1)/users", 0));
        assertEquals("api", t.find(HttpMethod.GET, "/api/users", Map.of()).handlerId());
        assertEquals("api", t.find(HttpMethod.GET, "/api/v1/users", Map.of()).handlerId());
    }

    @Test
    void headerConditionsArePartOfMatching() {
        RouteTable t = table(
                route("v1", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("X-Version", HeaderMatchType.EXACT, "v1")),
                route("v2", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("X-Version", HeaderMatchType.EXACT, "v2")));
        assertEquals("v1", t.find(HttpMethod.GET, "/x", Map.of("X-Version", "v1")).handlerId());
        assertEquals("v2", t.find(HttpMethod.GET, "/x", Map.of("x-version", "V2")).handlerId());
        assertThrows(NoMatchException.class,
                () -> t.find(HttpMethod.GET, "/x", Map.of("X-Version", "v3")));
    }

    @Test
    void presentAndAbsentHeaderConditions() {
        RouteTable t = table(
                route("authed", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("Authorization", HeaderMatchType.PRESENT, null)),
                route("anon", HttpMethod.GET, "/x", 1,
                        new HeaderCondition("Authorization", HeaderMatchType.ABSENT, null)));
        assertEquals("authed",
                t.find(HttpMethod.GET, "/x", Map.of("Authorization", "Bearer x")).handlerId());
        assertEquals("anon", t.find(HttpMethod.GET, "/x", Map.of()).handlerId());
    }

    @Test
    void regexHeaderCondition() {
        RouteTable t = table(
                route("json", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("Content-Type", HeaderMatchType.REGEX,
                                "application/.*json")));
        assertEquals("json", t.find(HttpMethod.GET, "/x",
                Map.of("Content-Type", "application/problem+json")).handlerId());
        assertThrows(NoMatchException.class,
                () -> t.find(HttpMethod.GET, "/x", Map.of("Content-Type", "text/plain")));
    }

    @Test
    void noMatchIsDistinctFromMethodNotAllowed() {
        RouteTable t = table(route("only-post", HttpMethod.POST, "/items", 0));
        assertThrows(NoMatchException.class,
                () -> t.find(HttpMethod.GET, "/missing", Map.of()));
        MethodNotAllowedException e = assertThrows(MethodNotAllowedException.class,
                () -> t.find(HttpMethod.GET, "/items", Map.of()));
        assertEquals(List.of("POST"), e.getAllowedMethods());
    }

    @Test
    void ambiguousEqualRankIsConflictNotSilentFallback() {
        RouteTable t = table(
                route("a", HttpMethod.GET, "/users/{id}", 0),
                route("b", HttpMethod.GET, "/users/{name}", 0));
        ConflictException e = assertThrows(ConflictException.class,
                () -> t.find(HttpMethod.GET, "/users/42", Map.of()));
        assertTrue(e.getConflictingHandlers().containsAll(List.of("a", "b")));
    }
}
