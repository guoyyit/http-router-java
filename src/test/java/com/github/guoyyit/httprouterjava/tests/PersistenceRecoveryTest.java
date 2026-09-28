package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceRecoveryTest {

    private Route route(String id, String method, String pattern) {
        return Route.create(id, method, pattern, Map.of(), "h-" + id, null);
    }

    @Test
    void restartRecoversLastCommittedTable(@TempDir Path dir) {
        Path file = dir.resolve("snapshot.json");
        RouterService first = new RouterService(new RouteSnapshotStore(file));
        first.recover();
        first.addRoute(route("a", "GET", "/users/{id}"));
        first.addRoute(route("b", "POST", "/orders"));
        Route h = Route.create("c", "GET", "/api", Map.of("X-Version", "1"), "hc", 5);
        first.addRoute(h);

        // simulate restart with a brand-new service over the same file
        RouterService second = new RouterService(new RouteSnapshotStore(file));
        second.recover();
        assertEquals(3, second.size());

        MatchResult r = second.match("GET", "/users/42", Map.of());
        assertEquals("h-a", ((MatchResult.Hit) r).route().handlerId());
        MatchResult r2 = second.match("GET", "/api", Map.of("x-version", "1"));
        assertEquals("hc", ((MatchResult.Hit) r2).route().handlerId());
        assertEquals(5, ((MatchResult.Hit) r2).route().priority());
    }

    @Test
    void rejectedUpdateDoesNotChangePersistedSnapshot(@TempDir Path dir) {
        Path file = dir.resolve("snapshot.json");
        RouterService svc = new RouterService(new RouteSnapshotStore(file));
        svc.recover();
        svc.addRoute(route("a", "GET", "/users/{id}"));

        assertThrows(RouteConflictException.class,
                () -> svc.addRoute(route("b", "GET", "/users/{name}")));

        RouterService restarted = new RouterService(new RouteSnapshotStore(file));
        restarted.recover();
        assertEquals(1, restarted.size());
        assertTrue(restarted.routes().get(0).id().equals("a"));
    }

    @Test
    void noSnapshotFileMeansEmptyTableNotFailure(@TempDir Path dir) {
        RouterService svc = new RouterService(new RouteSnapshotStore(dir.resolve("absent.json")));
        assertDoesNotThrow(svc::recover);
        assertEquals(0, svc.size());
    }

    @Test
    void corruptSnapshotFailsFastInsteadOfHalfLoading(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("snapshot.json");
        Files.writeString(file, "[{\"id\": \"a\", \"method\": \"GET\" , \"pattern\": \"/x\"}, {broken]");
        RouterService svc = new RouterService(new RouteSnapshotStore(file));
        assertThrows(RuntimeException.class, svc::recover); // fail fast, no partial table
    }

    @Test
    void rollbackRestoresPreviousTableAndPersistsIt(@TempDir Path dir) {
        Path file = dir.resolve("snapshot.json");
        RouterService svc = new RouterService(new RouteSnapshotStore(file));
        svc.recover();
        svc.addRoute(route("a", "GET", "/a"));
        svc.addRoute(route("b", "GET", "/b"));
        assertEquals(2, svc.size());

        svc.rollback(); // back to table containing only "a"
        assertEquals(1, svc.size());
        assertEquals("a", svc.routes().get(0).id());

        RouterService restarted = new RouterService(new RouteSnapshotStore(file));
        restarted.recover();
        assertEquals(1, restarted.size());
        assertEquals("a", restarted.routes().get(0).id());

        // repeated rollback walks back through the commit history
        svc.rollback();
        assertEquals(0, svc.size());
        assertThrows(IllegalStateException.class, svc::rollback); // history exhausted
    }

    @Test
    void rollbackHistorySurvivesRestart(@TempDir Path dir) {
        Path file = dir.resolve("snapshot.json");
        RouterService svc = new RouterService(new RouteSnapshotStore(file));
        svc.recover();
        svc.addRoute(route("a", "GET", "/a"));
        svc.addRoute(route("b", "GET", "/b"));
        assertEquals(2, svc.size());

        // restart: committed table and undo history both come back
        RouterService restarted = new RouterService(new RouteSnapshotStore(file));
        restarted.recover();
        assertEquals(2, restarted.size());
        assertEquals(2, restarted.snapshotCount());

        restarted.rollback();
        assertEquals(1, restarted.size());
        assertEquals("a", restarted.routes().get(0).id());
        restarted.rollback();
        assertEquals(0, restarted.size());
    }

    @Test
    void batchCommitSurvivesRestartAtScale(@TempDir Path dir) {
        Path file = dir.resolve("snapshot.json");
        RouterService svc = new RouterService(new RouteSnapshotStore(file));
        svc.recover();
        List<Route> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            many.add(route("r" + i, "GET", "/batch/" + i));
        }
        svc.update(many, List.of());

        RouterService restarted = new RouterService(new RouteSnapshotStore(file));
        restarted.recover();
        assertEquals(500, restarted.size());
        for (int i = 0; i < 500; i++) {
            MatchResult r = restarted.match("GET", "/batch/" + i, Map.of());
            assertTrue(r instanceof MatchResult.Hit, "route r" + i + " missing after restart");
        }
    }
}
