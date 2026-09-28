package com.github.guoyyit.httprouterjava.tests;

import com.github.guoyyit.httprouterjava.core.PersistenceException;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.persist.FileSnapshotStore;
import com.github.guoyyit.httprouterjava.persist.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSnapshotStoreTest {

    private Route route(String id) {
        return new Route(id, HttpMethod.GET, "/" + id, List.of(), 0);
    }

    @Test
    void savesAndLoadsLatestRoundTrip(@TempDir Path dir) {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 10);
        store.save(1, List.of(route("a"), route("b")));
        store.save(2, List.of(route("c")));

        Snapshot latest = store.loadLatest();
        assertEquals(2, latest.version());
        assertEquals(List.of("c"), latest.routes().stream().map(Route::getHandlerId).toList());
    }

    @Test
    void freshStoreHasNoSnapshot(@TempDir Path dir) {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 10);
        assertNull(store.loadLatest());
    }

    @Test
    void corruptPointerFallsBackToNewestVersionFile(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 10);
        store.save(1, List.of(route("a")));
        store.save(2, List.of(route("b")));
        // Simulate a torn pointer write.
        Files.writeString(dir.resolve(FileSnapshotStore.LATEST_FILE), "{not json");

        Snapshot latest = store.loadLatest();
        assertEquals(2, latest.version());
        assertEquals("b", latest.routes().get(0).getHandlerId());
    }

    @Test
    void corruptVersionFileFallsBackToOlder(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 10);
        store.save(1, List.of(route("a")));
        store.save(2, List.of(route("b")));
        Files.writeString(dir.resolve("routes-v2.json"), "garbage");

        Snapshot latest = store.loadLatest();
        assertEquals(1, latest.version());
        assertEquals("a", latest.routes().get(0).getHandlerId());
    }

    @Test
    void noTemporaryFilesRemainAfterSave(@TempDir Path dir) throws Exception {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 10);
        store.save(1, List.of(route("a")));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void prunesOldVersionsBeyondKeepLimit(@TempDir Path dir) {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 3);
        for (long v = 1; v <= 6; v++) {
            store.save(v, List.of(route("r" + v)));
        }
        List<Long> versions = store.availableVersions();
        assertEquals(List.of(6L, 5L, 4L), versions);
        assertThrows(PersistenceException.class, () -> store.rollbackTo(1));
        Snapshot s = store.rollbackTo(5);
        assertEquals("r5", s.routes().get(0).getHandlerId());
    }

    @Test
    void rollbackToMissingVersionThrows(@TempDir Path dir) {
        FileSnapshotStore store = new FileSnapshotStore(dir.toString(), 10);
        assertThrows(PersistenceException.class, () -> store.rollbackTo(99));
    }
}
