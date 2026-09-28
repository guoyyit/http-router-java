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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class PerfNumbersTest {

    @Test
    void printNumbers(@TempDir Path dir) {
        for (int size : new int[]{1_000, 5_000, 20_000}) {
            DefaultRouterService service = new DefaultRouterService(
                    new PathPatternParser(),
                    new RouteValidator(new PathPatternParser()),
                    new FileSnapshotStore(dir.resolve("p" + size).toString(), 2));
            List<Route> routes = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                routes.add(new Route("h" + i, HttpMethod.GET,
                        "/items/" + i + "/sub/{id:[0-9]+}", List.of(), 0));
            }
            long t0 = System.nanoTime();
            service.replaceAll(routes);
            long buildMs = (System.nanoTime() - t0) / 1_000_000;
            for (int i = 0; i < 5_000; i++) {
                service.match(HttpMethod.GET, "/items/" + (i % size) + "/sub/7", Map.of());
            }
            int probes = 50_000;
            t0 = System.nanoTime();
            for (int i = 0; i < probes; i++) {
                service.match(HttpMethod.GET, "/items/" + (i % size) + "/sub/7", Map.of());
            }
            long us = (System.nanoTime() - t0) / 1_000;
            System.out.println("PERF size=" + size + " build=" + buildMs
                    + "ms avg=" + (us * 1000.0 / probes) + "ns");
        }
    }
}
