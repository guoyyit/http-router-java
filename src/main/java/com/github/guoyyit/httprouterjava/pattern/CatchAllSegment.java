package com.github.guoyyit.httprouterjava.pattern;

/**
 * Trailing catch-all {@code **}: matches zero or more remaining segments.
 * May only appear as the last segment of a pattern.
 */
public record CatchAllSegment(String variable) implements Segment {
}
