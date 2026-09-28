package com.github.guoyyit.httprouterjava.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists committed route tables as JSON on disk.
 *
 * Writes are atomic: serialize to a temp file in the same directory, then move
 * it over the target (atomic move when the filesystem supports it). A crash
 * therefore leaves either the old table or the new one, never a half-written
 * file. On startup a missing snapshot means an empty table; a corrupt snapshot
 * fails fast instead of silently loading partial state.
 */
public class RouteSnapshotStore {

    private final Path file;
    private final Path tempFile;
    private final Path historyFile;
    private final Path historyTempFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteSnapshotStore(Path file) {
        this.file = file;
        this.tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        this.historyFile = file.resolveSibling(file.getFileName() + ".history.json");
        this.historyTempFile = file.resolveSibling(file.getFileName() + ".history.tmp");
    }

    /** Result of recovery: the committed table and, best-effort, the undo history. */
    public record Recovered(List<Route> current, List<List<Route>> undoHistory) {}

    public Recovered recover() {
        List<Route> current = load().orElse(List.of());
        List<List<Route>> history = List.of();
        if (Files.exists(historyFile)) {
            try {
                JsonNode root = mapper.readTree(Files.readAllBytes(historyFile));
                if (root != null && root.isArray()) {
                    List<List<Route>> stack = new ArrayList<>();
                    for (JsonNode table : root) {
                        if (table.isArray()) {
                            List<Route> t = new ArrayList<>();
                            for (JsonNode n : table) {
                                t.add(parseRoute(n));
                            }
                            stack.add(t);
                        }
                    }
                    history = stack;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read history snapshot: " + historyFile, e);
            }
        }
        return new Recovered(current, history);
    }

    public Optional<List<Route>> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file) );
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("Snapshot root must be a JSON array: " + file);
            }
            List<Route> routes = new ArrayList<>();
            for (JsonNode n : root) {
                routes.add(parseRoute(n));
            }
            return Optional.of(routes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read route snapshot: " + file, e);
        }
    }

    private Route parseRoute(JsonNode n) {
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode h = n.get("headers");
        if (h != null && h.isObject()) {
            h.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        }
        return Route.create(
                text(n, "id"),
                text(n, "method"),
                text(n, "pattern"),
                headers,
                text(n, "handlerId"),
                n.has("priority") ? n.get("priority").asInt() : 0);
    }

    /** Persist the committed table plus the full undo history. */
    public void saveAll(List<Route> routes, List<List<Route>> undoHistory) {
        save(routes);
        try {
            if (historyFile.getParent() != null && !Files.exists(historyFile.getParent())) {
                Files.createDirectories(historyFile.getParent());
            }
            List<List<Map<String, Object>>> payload = new ArrayList<>();
            for (List<Route> table : undoHistory) {
                payload.add(toPayload(table));
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
            Files.write(historyTempFile, bytes);
            try {
                Files.move(historyTempFile, historyFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(historyTempFile, historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist history snapshot: " + historyFile, e);
        }
    }

    public synchronized void save(List<Route> routes) {
        try {
            if (file.getParent() != null && !Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toPayload(routes));
            Files.write(tempFile, bytes);
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist route snapshot: " + file, e);
        }
    }

    private static List<Map<String, Object>> toPayload(List<Route> routes) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Route r : routes) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (HeaderCondition c : r.headerConditions()) {
                headers.put(c.name(), c.expression());
            }
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("id", r.id());
            obj.put("method", r.method().name());
            obj.put("pattern", r.pattern().raw());
            obj.put("headers", headers);
            obj.put("handlerId", r.handlerId());
            obj.put("priority", r.priority());
            payload.add(obj);
        }
        return payload;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
