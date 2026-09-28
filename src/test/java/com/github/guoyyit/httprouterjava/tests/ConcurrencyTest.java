package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.DefaultRouterService;
import com.github.guoyyit.httprouterjava.core.RouteValidator;
import com.github.guoyyit.httprouterjava.model.HeaderCondition;
import com.github.guoyyit.httprouterjava.model.HeaderMatchType;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.MatchResult;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.persist.FileSnapshotStore;
import com.github.guoyyit.httprouterjava.pattern.PathPatternParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private static final Map<String, String> WITH_HEADER = Map.of("X", "present");

    private static DefaultRouterService newService(Path dir) {
        return new DefaultRouterService(
                new PathPatternParser(),
                new RouteValidator(new PathPatternParser()),
                new FileSnapshotStore(dir.toString(), 50));
    }

    private static Route generationRoute(int n) {
        // Every generation routes the same path /p to handler h<n> with
        // priority n. A match therefore yields handler and priority from one
        // and the same immutable snapshot.
        return new Route("h" + n, HttpMethod.GET, "/p",
                List.of(new HeaderCondition("X", HeaderMatchType.PRESENT, null)), n);
    }

    /**
     * During continuous atomic replacements a reader must either see the
     * previous generation or the new one in full, never a partial mix, and a
     * lookup must never observe a torn table (which would surface as an
     * exception or an inconsistent handler/priority pair).
     */
    @Test
    void readersAlwaysSeeWholeGenerations(@TempDir Path dir) throws Exception {
        DefaultRouterService service = newService(dir);
        service.replaceAll(List.of(generationRoute(0)));

        int readers = 8;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger tornReads = new AtomicInteger();
        AtomicInteger matchCount = new AtomicInteger();
        AtomicBoolean sawOld = new AtomicBoolean();
        AtomicBoolean sawNew = new AtomicBoolean();
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int n = 1; n <= iterations; n++) {
                    service.replaceAll(List.of(generationRoute(n)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        long v = service.currentVersion();
                        MatchResult result =
                                service.match(HttpMethod.GET, "/p", WITH_HEADER);
                        matchCount.incrementAndGet();
                        // handler id and priority must come from the same generation
                        if (!result.handlerId().equals("h" + result.priority())) {
                            tornReads.incrementAndGet();
                        }
                        if (v < iterations) {
                            if (result.priority() < iterations) {
                                sawOld.set(true);
                            }
                            if (result.priority() > 0) {
                                sawNew.set(true);
                            }
                        }
                        if (v >= iterations && result.priority() == iterations) {
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    tornReads.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(90, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(0, tornReads.get(), "torn/inconsistent reads observed");
        assertTrue(matchCount.get() > 0, "readers performed no matches");
        assertTrue(sawOld.get() && sawNew.get(),
                "readers did not observe both old and new generations");
        long expectedVersion = iterations + 1L; // initial seed + one commit per replace
        assertEquals(expectedVersion, service.currentVersion());
        assertEquals("h" + iterations,
                service.match(HttpMethod.GET, "/p", WITH_HEADER).handlerId());
    }

    /**
     * 1000 matches against the committed snapshot while another operation is
     * pending must complete promptly: the read path takes no write lock.
     */
    @Test
    void readsDoNotBlockOnPendingWrite(@TempDir Path dir) throws Exception {
        DefaultRouterService service = newService(dir);
        service.replaceAll(List.of(generationRoute(0)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch pendingStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> pending = pool.submit(() -> {
            pendingStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        pendingStarted.await();
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            MatchResult m = service.match(HttpMethod.GET, "/p", WITH_HEADER);
            assertEquals("h0", m.handlerId());
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);
        release.countDown();
        pending.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(elapsed.toMillis() < 1000,
                "reads blocked while a write was pending: " + elapsed);
    }
}
