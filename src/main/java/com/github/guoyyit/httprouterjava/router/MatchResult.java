package com.github.guoyyit.httprouterjava.router;

import java.util.List;

/** Outcome of a route lookup: exactly one of Hit / NoMatch / Conflict. */
public sealed interface MatchResult {

    record Hit(Route route) implements MatchResult {}

    /** No route matched; reason distinguishes method/path/header misses. */
    record NoMatch(String reason) implements MatchResult {}

    /** Multiple routes matched with equal top priority; never silently picks one. */
    record Conflict(List<Route> candidates, String reason) implements MatchResult {}
}
