package com.github.guoyyit.httprouterjava.persist;

import com.github.guoyyit.httprouterjava.model.Route;

import java.util.List;

/** A persisted route table version. */
public record Snapshot(long version, List<Route> routes) {
}
