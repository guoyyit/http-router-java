package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.ConflictException;
import com.github.guoyyit.httprouterjava.core.DefaultRouterService;
import com.github.guoyyit.httprouterjava.core.NoMatchException;
import com.github.guoyyit.httprouterjava.core.RouteValidator;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.persist.FileSnapshotStore;
import com.github.guoyyit.httprouterjava.persist.Snapshot;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRouterServiceTest {

    private DefaultRouterService service;

    @TempDir
    Path dir;

    private static Route route(String id, HttpMethod m, String path, int priority) {
        return new Route(id, m, path, List.of(), priority);
    }

    @BeforeEach
    void setUp() {
        service = newService(dir);
    }

    private static DefaultRouterService newService(Path dir) {
        return new DefaultRouterService(
                new PathPatternParser(),
                new RouteValidator(new PathPatternParser()),
                new FileSnapshotStore(dir.toString(), 10));
    }

    @Test
    void addMatchRemoveLifecycle() {
        service.addRoute(route("u", HttpMethod.GET, "/users", 0));
        assertEquals("u", service.match(HttpMethod.GET, "/users", Map.of()).handlerId());
        assertEquals(1, service.currentVersion());

        service.removeRoute("u");
        assertThrows(NoMatchException.class,
                () -> service.match(HttpMethod.GET, "/users", Map.of()));
        assertEquals(2, service.currentVersion());
    }

    @Test
    void rejectedAddKeepsOldTableIntact() {
        service.addRoute(route("a", HttpMethod.GET, "/users/{id}", 0));
        assertThrows(ConflictException.class,
                () -> service.addRoute(route("b", HttpMethod.GET, "/users/{id}", 0)));

        assertEquals(1, service.currentVersion());
        assertEquals(1, service.snapshot().size());
        // Old table still fully functional.
        assertEquals("a", service.match(HttpMethod.GET, "/users/9", Map.of()).handlerId());
    }

    @Test
    void rejectedReplaceKeepsOldTableIntact() {
        service.addRoute(route("a", HttpMethod.GET, "/x", 0));
        assertThrows(ConflictException.class, () -> service.replaceAll(List.of(
                route("p", HttpMethod.GET, "/y", 0),
                route("q", HttpMethod.GET, "/y", 0))));

        assertEquals(1, service.currentVersion());
        assertEquals(List.of("a"),
                service.snapshot().stream().map(Route::getHandlerId).toList());
    }

    @Test
    void removeMissingHandlerIsAnError() {
        assertThrows(NoMatchException.class, () -> service.removeRoute("nope"));
    }

    @Test
    void replaceAllCommitsAtomically() {
        service.replaceAll(List.of(
                route("a", HttpMethod.GET, "/a", 0),
                route("b", HttpMethod.GET, "/b", 1)));
        assertEquals(1, service.currentVersion());
        assertEquals(2, service.snapshot().size());
    }

    @Test
    void restoreLatestReproducesCommittedTable() {
        service.addRoute(route("a", HttpMethod.GET, "/a", 0));
        service.addRoute(route("b", HttpMethod.GET, "/b", 0));
        service.addRoute(route("c", HttpMethod.GET, "/c", 0));

        DefaultRouterService restarted = newService(dir);
        restarted.restoreLatest();

        assertEquals(3, restarted.currentVersion());
        assertEquals(3, restarted.snapshot().size());
        assertEquals(List.of("a", "b", "c"),
                restarted.snapshot().stream().map(Route::getHandlerId).toList());
        assertEquals("b",
                restarted.match(HttpMethod.GET, "/b", Map.of()).handlerId());
    }

    @Test
    void rollbackRestoresHistoricalVersionAndAdvancesVersionNumber() {
        service.addRoute(route("a", HttpMethod.GET, "/a", 0)); // v1
        service.addRoute(route("b", HttpMethod.GET, "/b", 0)); // v2
        service.removeRoute("a");                                // v3

        Snapshot s = service.rollback(1);
        assertEquals(4, s.version());
        assertEquals(List.of("a"),
                s.routes().stream().map(Route::getHandlerId).toList());
        assertEquals(4, service.currentVersion());
        assertEquals("a", service.match(HttpMethod.GET, "/a", Map.of()).handlerId());
        assertTrue(service.snapshot().stream().noneMatch(r -> r.getHandlerId().equals("b")));
    }
}
