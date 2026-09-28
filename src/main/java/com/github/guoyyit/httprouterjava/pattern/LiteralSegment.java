package com.github.guoyyit.httprouterjava.pattern;

/** Exact, case-sensitive path segment such as {@code users}. */
public record LiteralSegment(String value) implements Segment {
}
