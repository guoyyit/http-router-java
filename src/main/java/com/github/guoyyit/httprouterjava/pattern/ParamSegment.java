package com.github.guoyyit.httprouterjava.pattern;

import java.util.regex.Pattern;

/**
 * Parameter segment {@code {name}} or {@code {name:regex}}. Matches exactly one
 * request segment; when a regex is present the segment must fully match it.
 */
public record ParamSegment(String name, Pattern constraint) implements Segment {
}
