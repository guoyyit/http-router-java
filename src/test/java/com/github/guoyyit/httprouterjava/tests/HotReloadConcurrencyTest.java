package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.router.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HotReloadConcurrencyTest {

    private RouterService newService(Path dir, String name) {
        RouterService svc = new RouterService(new RouteSnapshotStore(dir.resolve(name)));
        svc.recover();
        return svc;
    }

    private Route route(String id, String path) {
        return Route.create(id, "GET", path, Map.of(), "h-" + id, 0);
    }

    /**
     * During alternating updates the same path is served by handler v1 or v2.
     * Readers must always get HIT (never a torn table where the route vanished).
     */
    @Test
    void concurrentUpdatesAreAtomicallyVisibleToReaders(@TempDir Path dir) throws Exception {
        RouterService svc = newService(dir, "snap.json");
        svc.addRoute(route("v1", "/svc"));

        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger anomalies = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        // 6 readers
        for (int t = 0; t < 6; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (System.nanoTime() < end) {
                        MatchResult r = svc.match("GET", "/svc", Map.of());
                        if (r instanceof MatchResult.NoMatch || r instanceof MatchResult.Conflict) {
                            anomalies.incrementAndGet();
                        } else if (r instanceof MatchResult.Hit h) {
                            String id = h.route().id();
                            if (!id.equals("v1") && !id.equals("v2")) {
                                anomalies.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        // 1 writer alternating tables atomically
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    svc.update(List.of(route("v2", "/svc")), List.of("v1"));
                    svc.update(List.of(route("v1", "/svc")), List.of("v2"));
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        assertNull(error.get(), () -> "worker exception: " + error.get());
        assertEquals(0, anomalies.get(), "readers observed a torn/intermediate table");
    }

    /** Concurrent conflicting registrations: either one winner, or both rejected; never corruption. */
    @Test
    void conflictingConcurrentAddsSerializeSafely(@TempDir Path dir) throws Exception {
        RouterService svc = newService(dir, "snap.json");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        svc.addRoute(route("dup", "/same/path"));
                        accepted.incrementAndGet();
                    } catch (RouteConflictException e) {
                        rejected.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, accepted.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, svc.size());
    }

    /** Read throughput must stay stable while writers keep committing. */
    @Test
    void readsStayResponsiveUnderHeavyWrites(@TempDir Path dir) throws Exception {
        RouterService svc = newService(dir, "snap.json");
        for (int i = 0; i < 50; i++) {
            svc.addRoute(route("keep-" + i, "/keep/" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger anomalies = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (System.nanoTime() < end) {
                        int i = (reads.get()) % 50;
                        MatchResult r = svc.match("GET", "/keep/" + i, Map.of());
                        reads.incrementAndGet();
                        if (!(r instanceof MatchResult.Hit)) {
                            anomalies.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 30; i++) {
                    String id = "temp-" + i;
                    svc.addRoute(route(id, "/temp/" + i));
                    svc.removeRoute(id);
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(0, anomalies.get());
        assertTrue(reads.get() > 10_000, "expected substantial read volume, got " + reads.get());
        assertEquals(50, svc.size()); // temp routes fully removed
    }

    /**
     * Overlapping exact + wildcard rules under hot updates: readers of "/files"
     * must see either the old table (wildcard only) or the new table (exact wins),
     * never a torn state, a conflict, or a miss.
     */
    @Test
    void overlappingExactAndWildcardStayConsistentDuringUpdates(@TempDir Path dir) throws Exception {
        RouterService svc = newService(dir, "snap.json");
        svc.addRoute(route("wild", "/files/*"));

        int iterations = 150;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger anomalies = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < 6; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (System.nanoTime() < end) {
                        MatchResult r = svc.match("GET", "/files", Map.of());
                        if (r instanceof MatchResult.Hit h) {
                            String id = h.route().id();
                            // old table: only wild; new table: exact must win over wild
                            if (!id.equals("wild") && !id.equals("exact")) {
                                anomalies.incrementAndGet();
                            }
                        } else {
                            anomalies.incrementAndGet();
                        }
                        // deeper path is always served by the wildcard rule
                        MatchResult deep = svc.match("GET", "/files/a/b", Map.of());
                        if (!(deep instanceof MatchResult.Hit dh) || !dh.route().id().equals("wild")) {
                            anomalies.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    svc.addRoute(route("exact", "/files"));
                    svc.removeRoute("exact");
                }
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        assertNull(error.get(), () -> "worker exception: " + error.get());
        assertEquals(0, anomalies.get(), "readers observed a torn/intermediate table");

        // final state: exact registered -> /files deterministically hits exact
        svc.addRoute(route("exact", "/files"));
        MatchResult r = svc.match("GET", "/files", Map.of());
        assertTrue(r instanceof MatchResult.Hit);
        assertEquals("exact", ((MatchResult.Hit) r).route().id());
    }

    /** A rejected conflicting update must not disturb in-flight readers or the live table. */
    @Test
    void rejectedUpdateLeavesLiveTableServing(@TempDir Path dir) throws Exception {
        RouterService svc = newService(dir, "snap.json");
        svc.addRoute(route("exact", "/files"));
        svc.addRoute(route("wild", "/files/*"));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger anomalies = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (System.nanoTime() < end) {
                        MatchResult r = svc.match("GET", "/files", Map.of());
                        if (!(r instanceof MatchResult.Hit h) || !h.route().id().equals("exact")) {
                            anomalies.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        try {
                            // ambiguous vs itself-keyed duplicate: always rejected
                            svc.addRoute(route("wild-dup", "/files/*"));
                        } catch (RouteConflictException expected) {
                            // rejected: live table must stay intact
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads did not finish");
        assertNull(error.get(), () -> "worker exception: " + error.get());
        assertEquals(0, anomalies.get(), "rejected updates disturbed the live table");
        assertEquals(2, svc.size());
    }
}
