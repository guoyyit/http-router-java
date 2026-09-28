package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.DefaultRouterService;
import com.github.guoyyit.httprouterjava.core.RouteValidator;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.persist.FileSnapshotStore;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.github.guoyyit.httprouterjava.core.NoMatchException;

/**
 * Simulates a process restart: a second service instance pointed at the same
 * snapshot directory must recover the last committed table with no manual
 * intervention, and failed commits before the crash must leave no partial
 * state to recover.
 */
class RestartRecoveryIntegrationTest {

    private static DefaultRouterService service(Path dir) {
        return new DefaultRouterService(
                new PathPatternParser(),
                new RouteValidator(new PathPatternParser()),
                new FileSnapshotStore(dir.toString(), 10));
    }

    @Test
    void newInstanceRecoversLastCommittedTable(@TempDir Path dir) {
        DefaultRouterService first = service(dir);
        first.addRoute(new Route("a", HttpMethod.GET, "/a", List.of(), 0));
        first.addRoute(new Route("b", HttpMethod.POST, "/b/{id}", List.of(), 2));

        // Simulate restart: brand new instance, same disk directory.
        DefaultRouterService second = service(dir);
        second.restoreLatest();

        assertEquals(first.currentVersion(), second.currentVersion());
        assertEquals(2, second.snapshot().size());
        assertEquals("a", second.match(HttpMethod.GET, "/a", Map.of()).handlerId());
        assertEquals("b", second.match(HttpMethod.POST, "/b/7", Map.of()).handlerId());
        assertEquals(Map.of("id", "7"),
                second.match(HttpMethod.POST, "/b/7", Map.of()).pathVariables());
    }

    @Test
    void rejectedCommitBeforeCrashLeavesRecoverableLastGoodTable(@TempDir Path dir) {
        DefaultRouterService first = service(dir);
        first.addRoute(new Route("a", HttpMethod.GET, "/x", List.of(), 0));
        // Rejected: conflict, nothing committed beyond version 1.
        assertThrows(RuntimeException.class, () ->
                first.addRoute(new Route("b", HttpMethod.GET, "/x", List.of(), 0)));

        DefaultRouterService second = service(dir);
        second.restoreLatest();

        assertEquals(1, second.currentVersion());
        assertEquals(1, second.snapshot().size());
        assertEquals("a", second.match(HttpMethod.GET, "/x", Map.of()).handlerId());
    }

    @Test
    void emptyDirectoryStartsEmpty(@TempDir Path dir) {
        DefaultRouterService fresh = service(dir);
        fresh.restoreLatest();
        assertEquals(0, fresh.currentVersion());
        assertThrows(NoMatchException.class,
                () -> fresh.match(HttpMethod.GET, "/anything", Map.of()));
    }
}
