package com.github.guoyyit.httprouterjava.pattern;

/**
 * One element of a concrete (optional-groups already expanded) path pattern.
 */
public sealed interface Segment
        permits LiteralSegment, ParamSegment, WildcardSegment, CatchAllSegment {
}
