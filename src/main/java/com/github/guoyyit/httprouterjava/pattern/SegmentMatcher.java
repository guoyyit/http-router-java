package com.github.guoyyit.httprouterjava.pattern;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches concrete segment sequences against request segments and extracts
 * path variables / catch-all tails.
 */
public final class SegmentMatcher {

    private SegmentMatcher() {
    }

    /**
     * Attempt to match one concrete pattern sequence against request segments.
     *
     * @return extracted variables on success, {@code null} on mismatch
     */
    public static Map<String, String> match(List<Segment> pattern, List<String> request) {
        int last = pattern.size() - 1;
        boolean catchAll = last >= 0 && pattern.get(last) instanceof CatchAllSegment;
        int fixedLen = catchAll ? last : pattern.size();
        if (request.size() < fixedLen) {
            return null;
        }
        if (!catchAll && request.size() != pattern.size()) {
            return null;
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < fixedLen; i++) {
            if (!matchFixed(pattern.get(i), request.get(i), vars)) {
                return null;
            }
        }
        if (catchAll) {
            CatchAllSegment c = (CatchAllSegment) pattern.get(last);
            List<String> tail = request.subList(fixedLen, request.size());
            vars.put(c.variable(), String.join("/", tail));
        }
        return vars;
    }

    private static boolean matchFixed(Segment segment, String token, Map<String, String> vars) {
        if (segment instanceof LiteralSegment lit) {
            return lit.value().equals(token);
        }
        if (segment instanceof WildcardSegment) {
            return true;
        }
        if (segment instanceof ParamSegment p) {
            if (p.constraint() != null
                    && !p.constraint().matcher(token).matches()) {
                return false;
            }
            vars.put(p.name(), token);
            return true;
        }
        // Catch-all is only handled by the caller as the trailing segment.
        return false;
    }
}
