package com.github.guoyyit.httprouterjava.pattern;

import java.util.List;
import java.util.Map;

/**
 * A parsed path pattern. Parenthesised optional fragments are expanded into a
 * finite set of concrete segment sequences ({@code alternatives}); a request
 * matches if any alternative matches.
 */
public final class PathPattern {

    private final String raw;
    private final List<List<Segment>> alternatives;

    public PathPattern(String raw, List<List<Segment>> alternatives) {
        this.raw = raw;
        this.alternatives = List.copyOf(alternatives);
    }

    public String getRaw() {
        return raw;
    }

    public List<List<Segment>> getAlternatives() {
        return alternatives;
    }

    /**
     * Match request segments against every expanded alternative.
     *
     * @return extracted variables of the first matching alternative,
     *         or {@code null} when none matches
     */
    public Map<String, String> match(List<String> requestSegments) {
        for (List<Segment> alternative : alternatives) {
            Map<String, String> vars = SegmentMatcher.match(alternative, requestSegments);
            if (vars != null) {
                return vars;
            }
        }
        return null;
    }
}
