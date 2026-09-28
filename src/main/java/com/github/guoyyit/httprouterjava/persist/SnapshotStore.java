package com.github.guoyyit.httprouterjava.persist;

import com.github.guoyyit.httprouterjava.model.Route;

import java.util.List;

/**
 * Persists committed route tables durably and restores the latest one on
 * startup. Writes are atomic (temp file + move); a crashed/half-written file
 * is never read — recovery falls back to the previous complete snapshot.
 */
public interface SnapshotStore {

    /** Persist one committed version as the new latest snapshot. */
    void save(long version, List<Route> routes);

    /** The latest successfully persisted snapshot, or {@code null} if none. */
    Snapshot loadLatest();

    /**
     * Restore a previously kept historical version as the latest table.
     *
     * @throws PersistenceException if the version does not exist
     */
    Snapshot rollbackTo(long version);

    /** Versions available for rollback, newest first. */
    List<Long> availableVersions();
}
