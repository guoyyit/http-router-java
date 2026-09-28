package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.ConflictException;
import com.github.guoyyit.httprouterjava.core.InvalidRouteException;
import com.github.guoyyit.httprouterjava.core.RouteValidator;
import com.github.guoyyit.httprouterjava.model.HeaderCondition;
import com.github.guoyyit.httprouterjava.model.HeaderMatchType;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteValidatorTest {

    private final RouteValidator validator = new RouteValidator(new PathPatternParser());

    private Route route(String id, HttpMethod m, String path, int priority,
                        HeaderCondition... headers) {
        return new Route(id, m, path, List.of(headers), priority);
    }

    @Test
    void rejectsMalformedSingleRoutes() {
        assertThrows(InvalidRouteException.class,
                () -> validator.validateOne(route(" ", HttpMethod.GET, "/x", 0)));
        assertThrows(InvalidRouteException.class,
                () -> validator.validateOne(new Route("x", null, "/x", List.of(), 0)));
        assertThrows(InvalidRouteException.class,
                () -> validator.validateOne(route("x", HttpMethod.GET, "x", 0)));
        assertThrows(InvalidRouteException.class,
                () -> validator.validateOne(route("x", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("H", HeaderMatchType.EXACT, null))));
        assertThrows(InvalidRouteException.class,
                () -> validator.validateOne(route("x", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("H", HeaderMatchType.REGEX, "[0-9"))));
    }

    @Test
    void rejectsDuplicateHandlerId() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                route("dup", HttpMethod.GET, "/a", 0),
                route("dup", HttpMethod.POST, "/b", 5))));
    }

    @Test
    void rejectsIdenticalRouteTwice() {
        assertThrows(ConflictException.class, () -> validator.validateSet(List.of(
                route("a", HttpMethod.GET, "/users", 0),
                route("b", HttpMethod.GET, "/users", 0))));
    }

    @Test
    void rejectsOverlappingSamePriorityButAcceptsDifferentPriority() {
        List<Route> overlap = List.of(
                route("a", HttpMethod.GET, "/users/{id}", 0),
                route("b", HttpMethod.GET, "/users/{id}", 10));
        assertDoesNotThrow(() -> validator.validateSet(overlap));

        List<Route> same = List.of(
                route("a", HttpMethod.GET, "/users/{id}", 5),
                route("b", HttpMethod.GET, "/users/{id}", 5));
        assertThrows(ConflictException.class, () -> validator.validateSet(same));
    }

    @Test
    void mutuallyExclusiveHeadersDoNotConflict() {
        List<Route> routes = List.of(
                route("v1", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("X-Version", HeaderMatchType.EXACT, "v1")),
                route("v2", HttpMethod.GET, "/x", 0,
                        new HeaderCondition("X-Version", HeaderMatchType.EXACT, "v2")));
        assertDoesNotThrow(() -> validator.validateSet(routes));
    }

    @Test
    void differentMethodsDoNotConflict() {
        List<Route> routes = List.of(
                route("g", HttpMethod.GET, "/x", 0),
                route("p", HttpMethod.POST, "/x", 0));
        assertDoesNotThrow(() -> validator.validateSet(routes));
    }

    @Test
    void catchAllOverlapIsDetected() {
        // Same-prefix catch-all and exact route at the same prefix rank-tie on
        // the zero-tail concrete path, so this is a genuine ambiguity.
        List<Route> routes = List.of(
                route("a", HttpMethod.GET, "/files/**", 0),
                route("b", HttpMethod.GET, "/files", 0));
        assertThrows(ConflictException.class, () -> validator.validateSet(routes));
    }

    @Test
    void catchAllVsDeeperSpecificRouteIsAllowed() {
        // A deeper exact route always outranks the ancestor catch-all on the
        // shared concrete path, so they coexist (resolved at match time).
        List<Route> routes = List.of(
                route("a", HttpMethod.GET, "/files/**", 0),
                route("b", HttpMethod.GET, "/files/x/y", 0));
        assertDoesNotThrow(() -> validator.validateSet(routes));
    }

    @Test
    void literalVsParameterIsAllowedBecauseSpecificityDecides() {
        // Literal always outranks a parameter on the shared concrete path.
        List<Route> routes = List.of(
                route("me", HttpMethod.GET, "/users/me", 0),
                route("byId", HttpMethod.GET, "/users/{id:[0-9]+}", 0));
        assertDoesNotThrow(() -> validator.validateSet(routes));
    }

    @Test
    void parameterVsSingleWildcardIsAllowed() {
        // {id} (weight 2) always beats * (weight 1).
        List<Route> routes = List.of(
                route("p", HttpMethod.GET, "/u/{id}", 0),
                route("w", HttpMethod.GET, "/u/*", 0));
        assertDoesNotThrow(() -> validator.validateSet(routes));
    }

    @Test
    void disjointStaticPathsAreFine() {
        List<Route> routes = List.of(
                route("a", HttpMethod.GET, "/users", 0),
                route("b", HttpMethod.GET, "/orders", 0));
        assertDoesNotThrow(() -> validator.validateSet(routes));
    }
}
