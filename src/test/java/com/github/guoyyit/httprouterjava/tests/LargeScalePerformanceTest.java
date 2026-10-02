package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class LargeScalePerformanceTest {

    private Route route(String id, String method, String pattern) {
        return Route.create(id, method, pattern, Map.of(), "h-" + id, null);
    }

    /**
     * 10,000 routes: mostly deep static paths plus param/regex/wildcard mixes.
     * Registration happens in atomic batches; lookups stay trie-bounded.
     */
    @Test
    void tenThousandRoutesBatchLoadAndFastLookup(@TempDir Path dir) {
        RouterService svc = new RouterService(new RouteSnapshotStore(dir.resolve("big.json")));
        svc.recover();

        List<Route> batch = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            String a = "a" + (i % 100);
            String b = "b" + (i % 97);
            batch.add(route("r" + i, "GET", "/shops/" + a + "/items/" + b + "/sku/" + i));
            if (i % 1000 == 999) {
                svc.update(new ArrayList<>(batch), List.of());
                batch.clear();
            }
        }
        // add shared param + wildcard routes (must not conflict: different path shapes)
        List<Route> dynamic = List.of(
                route("byId", "GET", "/shops/{shopId}/items/{itemId}/by-id/{id:\\d+}"),
                route("catchShop", "GET", "/shops/{shopId}/files/*"),
                route("catchAll", "GET", "/assets/*"),
                route("health", "GET", "/healthz"));
        svc.update(dynamic, List.of());
        assertEquals(10_004, svc.size());

        // correctness on random registered paths
        for (int k = 0; k < 200; k++) {
            int i = ThreadLocalRandom.current().nextInt(10_000);
            String a = "a" + (i % 100);
            String b = "b" + (i % 97);
            MatchResult r = svc.match("GET", "/shops/" + a + "/items/" + b + "/sku/" + i, Map.of());
            assertTrue(r instanceof MatchResult.Hit, "miss for index " + i);
            assertEquals("r" + i, ((MatchResult.Hit) r).route().id());
        }

        // dynamic routes
        assertTrue(svc.match("GET", "/shops/a3/items/b5/by-id/42", Map.of()) instanceof MatchResult.Hit);
        MatchResult files = svc.match("GET", "/shops/a3/files/2024/q/x.pdf", Map.of());
        assertEquals("catchShop", ((MatchResult.Hit) files).route().id());
        MatchResult assets = svc.match("GET", "/assets/css/main.css", Map.of());
        assertEquals("catchAll", ((MatchResult.Hit) assets).route().id());

        // perf: average lookup must stay well below a linear-scan budget
        int samples = 100_000;
        long start = System.nanoTime();
        for (int k = 0; k < samples; k++) {
            int i = k % 10_000;
            String a = "a" + (i % 100);
            String b = "b" + (i % 97);
            svc.match("GET", "/shops/" + a + "/items/" + b + "/sku/" + i, Map.of());
        }
        long elapsed = System.nanoTime() - start;
        double avgMicros = elapsed / 1_000.0 / samples;
        System.out.printf("Average lookup over %d routes: %.3f us%n", 10_000, avgMicros);
        assertTrue(avgMicros < 100, "lookup too slow: " + avgMicros + " us average");
    }

    /** Adding to a 10k table must validate quickly; conflicts must still be detected. */
    @Test
    void conflictStillDetectedAtScale(@TempDir Path dir) {
        RouterService svc = new RouterService(new RouteSnapshotStore(dir.resolve("big.json")));
        svc.recover();
        List<Route> batch = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            batch.add(route("r" + i, "GET", "/k/" + i));
            if (i % 2000 == 1999) {
                svc.update(new ArrayList<>(batch), List.of());
                batch.clear();
            }
        }
        // duplicate of an existing route must be rejected even in a huge table
        assertThrows(RouteConflictException.class,
                () -> svc.addRoute(route("evil", "GET", "/k/1234")));
        // brand new non-overlapping route accepted
        svc.addRoute(route("fresh", "GET", "/brand/new/path"));
        assertEquals(10_001, svc.size());
    }

    /**
     * Large-scale table with overlap combinations (exact+wildcard, regex+param,
     * param+wildcard) must keep correct priority and fast lookups.
     */
    @Test
    void overlapCombinationsAtScale(@TempDir Path dir) {
        RouterService svc = new RouterService(new RouteSnapshotStore(dir.resolve("big.json")));
        svc.recover();

        List<Route> batch = new ArrayList<>();
        // 100 distinct wildcard tails, each covering a group of exact leaves
        for (int i = 0; i < 100; i++) {
            batch.add(route("wild" + i, "GET", "/shops/a" + i + "/items/*"));
        }
        svc.update(new ArrayList<>(batch), List.of());
        batch.clear();
        // 10,000 exact static leaves, each under one wildcard tail
        for (int i = 0; i < 10_000; i++) {
            batch.add(route("exact" + i, "GET", "/shops/a" + (i % 100) + "/items/" + i));
            if (i % 1000 == 999) {
                svc.update(new ArrayList<>(batch), List.of());
                batch.clear();
            }
        }
        // regex + param overlap at scale: /v/{i}/{n:\d+} beats /v/{i}/{x} on numeric values
        for (int i = 0; i < 500; i++) {
            batch.add(route("num" + i, "GET", "/v/" + i + "/{n:\\d+}"));
            batch.add(route("any" + i, "GET", "/v/" + i + "/{x}"));
        }
        svc.update(new ArrayList<>(batch), List.of());
        batch.clear();
        assertEquals(11_100, svc.size());

        // exact path hits the exact rule, not the wildcard tail
        for (int k = 0; k < 200; k++) {
            int i = ThreadLocalRandom.current().nextInt(10_000);
            MatchResult r = svc.match("GET", "/shops/a" + (i % 100) + "/items/" + i, Map.of());
            assertTrue(r instanceof MatchResult.Hit, "miss for exact index " + i);
            assertEquals("exact" + i, ((MatchResult.Hit) r).route().id());
        }
        // deeper path hits the wildcard tail
        for (int k = 0; k < 200; k++) {
            int i = ThreadLocalRandom.current().nextInt(10_000);
            MatchResult r = svc.match("GET", "/shops/a" + (i % 100) + "/items/" + i + "/extra", Map.of());
            assertTrue(r instanceof MatchResult.Hit, "miss for wildcard index " + i);
            assertEquals("wild" + (i % 100), ((MatchResult.Hit) r).route().id());
        }
        // regex beats param on numeric values
        for (int k = 0; k < 100; k++) {
            int i = ThreadLocalRandom.current().nextInt(500);
            MatchResult r = svc.match("GET", "/v/" + i + "/42", Map.of());
            assertTrue(r instanceof MatchResult.Hit);
            assertEquals("num" + i, ((MatchResult.Hit) r).route().id());
        }
        // param beats nothing on alpha values (regex does not match)
        for (int k = 0; k < 100; k++) {
            int i = ThreadLocalRandom.current().nextInt(500);
            MatchResult r = svc.match("GET", "/v/" + i + "/ab", Map.of());
            assertTrue(r instanceof MatchResult.Hit);
            assertEquals("any" + i, ((MatchResult.Hit) r).route().id());
        }

        // perf: lookups stay trie-bounded despite overlap candidates
        int samples = 100_000;
        long start = System.nanoTime();
        for (int k = 0; k < samples; k++) {
            int i = k % 10_000;
            svc.match("GET", "/shops/a" + (i % 100) + "/items/" + i, Map.of());
        }
        long elapsed = System.nanoTime() - start;
        double avgMicros = elapsed / 1_000.0 / samples;
        System.out.printf("Average lookup over %d overlap routes: %.3f us%n", 11_100, avgMicros);
        assertTrue(avgMicros < 100, "lookup too slow: " + avgMicros + " us average");
    }
}
