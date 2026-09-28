package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.DefaultRouterService;
import com.github.guoyyit.httprouterjava.core.RouteValidator;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.persist.FileSnapshotStore;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Predictable lookup performance on large route sets. The read path is a
 * linear scan over the immutable table with constant work per route; the test
 * pins the empirical expectation and guards against accidental super-linear
 * regressions when the table grows.
 */
class LargeScalePerformanceTest {

    private static Route route(String id, String path, int priority) {
        return new Route(id, HttpMethod.GET, path, List.of(), priority);
    }

    @Test
    void lookupStaysBoundedAcrossTableSizes(@TempDir Path dir) {
        int[] sizes = {5_000, 20_000};
        double previousAvgMicros = Double.MAX_VALUE;
        int previousSize = 0;

        for (int size : sizes) {
            DefaultRouterService service = new DefaultRouterService(
                    new PathPatternParser(),
                    new RouteValidator(new PathPatternParser()),
                    new FileSnapshotStore(dir.resolve("s" + size).toString(), 5));

            List<Route> routes = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                routes.add(route("h" + i,
                        "/items/" + i + "/sub/{id:[0-9]+}", 0));
            }
            long buildStart = System.nanoTime();
            service.replaceAll(routes);
            long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;

            // Warm up.
            for (int i = 0; i < 2_000; i++) {
                service.match(HttpMethod.GET, "/items/" + (i % size) + "/sub/42", Map.of());
            }

            int probes = 20_000;
            long start = System.nanoTime();
            for (int i = 0; i < probes; i++) {
                MatchResult m = service.match(
                        HttpMethod.GET, "/items/" + (i % size) + "/sub/7", Map.of());
                assertEquals("h" + (i % size), m.handlerId());
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            double avgMicros = elapsedMillis * 1000.0 / probes;

            // Rough linearity guard: 4x more routes may at most ~6x the latency
            // (loose bound to absorb JVM scheduling noise).
            if (previousSize > 0) {
                double ratio = avgMicros / previousAvgMicros;
                assertTrue(ratio < 6.0,
                        "lookup grew super-linearly: " + previousSize + " -> " + size
                                + " routes, avg " + previousAvgMicros + " -> "
                                + avgMicros + " us (ratio " + ratio + ")");
            }
            previousAvgMicros = avgMicros;
            previousSize = size;

            assertTrue(buildMillis < 30_000, "building " + size + " routes too slow");
            assertTrue(elapsedMillis < 60_000, "matching " + probes + " probes too slow");
        }
    }

    @Test
    void hundredRoutesWithWildcardsAndOptionalsStayFast(@TempDir Path dir) {
        DefaultRouterService service = new DefaultRouterService(
                new PathPatternParser(),
                new RouteValidator(new PathPatternParser()),
                new FileSnapshotStore(dir.toString(), 5));

        List<Route> routes = new ArrayList<>();
        int id = 0;
        for (int a = 0; a < 25; a++) {
            routes.add(route("w" + id++, "/api(/v1)/a" + a + "/*/details", id));
            routes.add(route("c" + id++, "/api(/v1)/a" + a + "/files/**:rest", id));
            routes.add(route("p" + id++, "/api(/v1)/a" + a + "/{id:[0-9]+}/posts/{postId}", id));
            routes.add(route("s" + id++, "/api/a" + a + "/static/segment", id));
        }
        service.replaceAll(routes);
        assertEquals(100, service.snapshot().size());

        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            int a = i % 25;
            MatchResult m = service.match(HttpMethod.GET,
                    "/api/v1/a" + a + "/files/x/y/z", Map.of());
            assertTrue(m.handlerId().startsWith("c"));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 10_000, "10k matches too slow: " + elapsedMillis + "ms");
    }
}
