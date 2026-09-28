package com.github.guoyyit.httprouterjava.router;

import java.util.regex.Pattern;

/**
 * One path segment in a route pattern.
 * Specificity rank (higher wins): STATIC > REGEX > PARAM > OPTIONAL > WILDCARD.
 */
public sealed interface Segment {

    boolean matches(String pathSegment);

    /** Specificity rank used by the priority comparator. */
    int rank();

    record StaticSeg(String value) implements Segment {
        @Override public boolean matches(String pathSegment) { return value.equals(pathSegment); }
        @Override public int rank() { return 5; }
    }

    record RegexSeg(String name, Pattern regex) implements Segment {
        @Override public boolean matches(String pathSegment) { return regex.matcher(pathSegment).matches(); }
        @Override public int rank() { return 4; }
    }

    record ParamSeg(String name) implements Segment {
        @Override public boolean matches(String pathSegment) { return true; }
        @Override public int rank() { return 3; }
    }

    /** Optional segment, only allowed in trailing position; wraps a param or regex segment. */
    record OptionalSeg(Segment inner) implements Segment {
        @Override public boolean matches(String pathSegment) { return inner.matches(pathSegment); }
        @Override public int rank() { return 2; }
    }

    /** Wildcard, matches zero or more remaining segments; only allowed as last segment. */
    record WildcardSeg(String name) implements Segment {
        @Override public boolean matches(String pathSegment) { return true; }
        @Override public int rank() { return 1; }
    }
}
