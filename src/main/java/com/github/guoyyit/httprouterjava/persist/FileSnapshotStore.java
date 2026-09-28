package com.github.guoyyit.httprouterjava.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.httprouterjava.core.PersistenceException;
import com.github.guoyyit.httprouterjava.model.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File-system backed snapshot store.
 *
 * <p>Directory layout:</p>
 * <ul>
 *   <li>{@code routes-v<N>.json} &mdash; immutable per-version snapshot files;</li>
 *   <li>{@code latest.json} &mdash; atomic pointer holding the newest committed
 *       version number.</li>
 * </ul>
 *
 * <p>Every write goes to a unique temporary file in the same directory and is
 * then moved into place atomically, so a crash can never leave a partially
 * written snapshot visible. If {@code latest.json} or the version it points to
 * is missing/corrupt, the newest readable version file is used instead. A
 * bounded number of historical versions is retained for rollback.</p>
 */
@Component
public class FileSnapshotStore implements SnapshotStore {

    /** Name of the atomic pointer file that names the latest version. */
    public static final String LATEST_FILE = "latest.json";
    private static final String VERSION_PREFIX = "routes-v";
    private static final String VERSION_SUFFIX = ".json";

    private final Path directory;
    private final int keepVersions;
    private final ObjectMapper mapper;

    public FileSnapshotStore(
            @Value("${router.snapshot.dir:./data/router}") String directory,
            @Value("${router.snapshot.keep:10}") int keepVersions) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.keepVersions = Math.max(1, keepVersions);
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new PersistenceException(
                    "cannot create snapshot directory: " + this.directory, e);
        }
    }

    @Override
    public synchronized void save(long version, List<Route> routes) {
        try {
            Path versionFile = versionFile(version);
            writeAtomically(versionFile, new Snapshot(version, routes));
            writeAtomically(latestFile(), new LatestPointer(version));
            prune(version);
        } catch (IOException e) {
            throw new PersistenceException("failed to persist version " + version, e);
        }
    }

    @Override
    public synchronized Snapshot loadLatest() {
        Long pointer = readPointer();
        if (pointer != null) {
            Snapshot snapshot = readVersion(pointer);
            if (snapshot != null) {
                return snapshot;
            }
        }
        // Pointer missing/corrupt or pointed file unreadable: fall back to the
        // newest readable per-version file.
        List<Long> versions = scanVersions();
        for (Long v : versions) {
            Snapshot snapshot = readVersion(v);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    @Override
    public synchronized Snapshot rollbackTo(long version) {
        Snapshot snapshot = readVersion(version);
        if (snapshot == null) {
            throw new PersistenceException("snapshot version not found: " + version, null);
        }
        return snapshot;
    }

    @Override
    public synchronized List<Long> availableVersions() {
        return scanVersions();
    }

    private void prune(long currentVersion) throws IOException {
        List<Long> versions = scanVersions();
        // Keep the newest keepVersions files, never deleting the current one.
        if (versions.size() <= keepVersions) {
            return;
        }
        List<Long> toDelete = versions.subList(keepVersions, versions.size());
        for (Long v : toDelete) {
            Files.deleteIfExists(versionFile(v));
        }
    }

    private Long readPointer() {
        Path file = latestFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            LatestPointer pointer = mapper.readValue(file.toFile(), LatestPointer.class);
            return pointer == null ? null : pointer.version;
        } catch (IOException e) {
            return null; // corrupt pointer: ignored, caller falls back
        }
    }

    private Snapshot readVersion(long version) {
        Path file = versionFile(version);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            SnapshotDto dto = mapper.readValue(file.toFile(), SnapshotDto.class);
            if (dto == null || dto.routes == null) {
                return null;
            }
            return new Snapshot(dto.version, List.copyOf(dto.routes));
        } catch (IOException | RuntimeException e) {
            return null; // corrupt version file: ignored
        }
    }

    private List<Long> scanVersions() {
        List<Long> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(VERSION_PREFIX) && n.endsWith(VERSION_SUFFIX))
                    .map(n -> n.substring(VERSION_PREFIX.length(),
                            n.length() - VERSION_SUFFIX.length()))
                    .forEach(n -> {
                        try {
                            versions.add(Long.parseLong(n));
                        } catch (NumberFormatException ignored) {
                            // unrelated file
                        }
                    });
        } catch (IOException e) {
            throw new PersistenceException("cannot scan snapshot directory", e);
        }
        versions.sort(Comparator.reverseOrder());
        return versions;
    }

    private void writeAtomically(Path target, Object payload) throws IOException {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        Path tmp = Files.createTempFile(directory,
                "." + target.getFileName().toString() + ".", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, perms);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem: rely on default permissions.
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), payload);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Path versionFile(long version) {
        return directory.resolve(VERSION_PREFIX + version + VERSION_SUFFIX);
    }

    private Path latestFile() {
        return directory.resolve(LATEST_FILE);
    }

    /** Content of {@code latest.json}. */
    private record LatestPointer(long version) {
    }

    /** On-disk shape of a per-version snapshot file. */
    private static final class SnapshotDto {
        public long version;
        public List<Route> routes;

        SnapshotDto() {
        }

        SnapshotDto(long version, List<Route> routes) {
            this.version = version;
            this.routes = routes;
        }
    }
}
