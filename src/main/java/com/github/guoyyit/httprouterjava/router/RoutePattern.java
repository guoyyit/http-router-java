package com.github.guoyyit.httprouterjava.router;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parsed route path pattern, e.g. "/users/{id:\d+}/files/{name?}".
 *
 * Syntax:
 *   /a/b            static segments
 *   /a/{id}         named parameter segment (matches exactly one segment)
 *   /a/{id:\d+}     parameter with regex constraint (full segment match)
 *   /a/{id?}        optional trailing segment (only trailing optionals allowed)
 *   /a/*            wildcard, matches zero or more remaining segments (must be last)
 */
public final class RoutePattern {

    private final String raw;
    private final List<Segment> segments;

    private RoutePattern(String raw, List<Segment> segments) {
        this.raw = raw;
        this.segments = List.copyOf(segments);
    }

    public static RoutePattern parse(String raw) {
        if (raw == null || !raw.startsWith("/")) {
            throw new PatternParseException("Pattern must start with '/': " + raw);
        }
        List<Segment> segments = new ArrayList<>();
        if (!raw.equals("/")) {
            String[] parts = raw.substring(1).split("/", -1);
            boolean optionalSeen = false;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean last = i == parts.length - 1;
                if (part.isEmpty()) {
                    throw new PatternParseException("Empty path segment in pattern: " + raw);
                }
                Segment seg = parseSegment(part, last, raw);
                if (seg instanceof Segment.WildcardSeg && !last) {
                    throw new PatternParseException("Wildcard must be the last segment: " + raw);
                }
                if (optionalSeen && !(seg instanceof Segment.OptionalSeg)) {
                    throw new PatternParseException("Only trailing optional segments are allowed: " + raw);
                }
                if (seg instanceof Segment.OptionalSeg) {
                    optionalSeen = true;
                }
                segments.add(seg);
            }
        }
        return new RoutePattern(raw, segments);
    }

    private static Segment parseSegment(String part, boolean last, String raw) {
        if (part.equals("*")) {
            return new Segment.WildcardSeg("*");
        }
        if (part.startsWith("{") || part.endsWith("}")) {
            if (!(part.startsWith("{") && part.endsWith("}"))) {
                throw new PatternParseException("Malformed parameter segment '" + part + "' in: " + raw);
            }
            String inner = part.substring(1, part.length() - 1);
            boolean optional = inner.endsWith("?");
            if (optional) {
                inner = inner.substring(0, inner.length() - 1);
            }
            Segment seg;
            int colon = inner.indexOf(':');
            if (colon >= 0) {
                if (optional) {
                    throw new PatternParseException("Optional regex segments are not supported: " + raw);
                }
                String name = inner.substring(0, colon);
                String regex = inner.substring(colon + 1);
                if (name.isEmpty() || regex.isEmpty()) {
                    throw new PatternParseException("Malformed regex segment '" + part + "' in: " + raw);
                }
                try {
                    seg = new Segment.RegexSeg(name, Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    throw new PatternParseException("Invalid regex in '" + part + "': " + e.getMessage());
                }
            } else {
                if (inner.isEmpty()) {
                    throw new PatternParseException("Parameter name must not be empty in: " + raw);
                }
                seg = new Segment.ParamSeg(inner);
            }
            return optional ? new Segment.OptionalSeg(seg) : seg;
        }
        if (part.contains("*") || part.contains("{") || part.contains("}")) {
            throw new PatternParseException("Illegal character in static segment '" + part + "': " + raw);
        }
        return new Segment.StaticSeg(part);
    }

    public String raw() { return raw; }

    public List<Segment> segments() { return segments; }

    /**
     * Expand trailing optional segments into all concrete segment lists.
     * A pattern with k trailing optionals yields k+1 expansions, shortest first.
     */
    public List<List<Segment>> expand() {
        int optionals = 0;
        for (int i = segments.size() - 1; i >= 0 && segments.get(i) instanceof Segment.OptionalSeg; i--) {
            optionals++;
        }
        List<List<Segment>> out = new ArrayList<>(optionals + 1);
        int base = segments.size() - optionals;
        for (int keep = 0; keep <= optionals; keep++) {
            List<Segment> exp = new ArrayList<>(base + keep);
            for (int i = 0; i < base + keep; i++) {
                Segment s = segments.get(i);
                exp.add(s instanceof Segment.OptionalSeg o ? o.inner() : s);
            }
            out.add(List.copyOf(exp));
        }
        return out;
    }

    /**
     * Total specificity order between two patterns that both matched the same
     * request path. Positive: this is more specific; negative: other is more
     * specific; zero: equally specific (potential ambiguity).
     *
     * Rules: compare segment ranks left to right, first difference decides
     * (static > regex > param > optional > wildcard). If one pattern is a
     * proper prefix of the other and all extra segments are skippable
     * (optional or wildcard), the shorter (exact) pattern wins.
     */
    public int compareSpecificity(RoutePattern other) {
        int common = Math.min(segments.size(), other.segments.size());
        for (int i = 0; i < common; i++) {
            int r = Integer.compare(segments.get(i).rank(), other.segments.get(i).rank());
            if (r != 0) {
                return r;
            }
        }
        if (segments.size() == other.segments.size()) {
            return 0;
        }
        List<Segment> longer = segments.size() > other.segments.size() ? segments : other.segments;
        boolean extraAllSkippable = longer.subList(common, longer.size()).stream()
                .allMatch(s -> s instanceof Segment.OptionalSeg || s instanceof Segment.WildcardSeg);
        if (extraAllSkippable) {
            // shorter pattern matched exactly -> more specific
            return segments.size() < other.segments.size() ? 1 : -1;
        }
        // cannot happen for two patterns that matched the same path; keep order deterministic
        return Integer.compare(segments.size(), other.segments.size());
    }

    /**
     * Conservative overlap check: can both patterns match some common path?
     * Different regex constraints are treated as potentially overlapping.
     */
    public boolean overlaps(RoutePattern other) {
        int max = Math.max(segments.size(), other.segments.size());
        for (int i = 0; i < max; i++) {
            boolean aDone = i >= segments.size();
            boolean bDone = i >= other.segments.size();
            if (aDone && bDone) {
                return true;
            }
            if (aDone) {
                return allSkippable(other.segments, i);
            }
            if (bDone) {
                return allSkippable(segments, i);
            }
            Segment a = segments.get(i);
            Segment b = other.segments.get(i);
            if (a instanceof Segment.WildcardSeg || b instanceof Segment.WildcardSeg) {
                return true; // wildcard absorbs the rest of any path
            }
            if (!compatible(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allSkippable(List<Segment> segs, int from) {
        for (int i = from; i < segs.size(); i++) {
            Segment s = segs.get(i);
            if (!(s instanceof Segment.OptionalSeg || s instanceof Segment.WildcardSeg)) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatible(Segment a, Segment b) {
        Segment x = a instanceof Segment.OptionalSeg o ? o.inner() : a;
        Segment y = b instanceof Segment.OptionalSeg o ? o.inner() : b;
        if (x instanceof Segment.StaticSeg s1 && y instanceof Segment.StaticSeg s2) {
            return s1.value().equals(s2.value());
        }
        if (x instanceof Segment.StaticSeg s && y instanceof Segment.RegexSeg r) {
            return r.regex().matcher(s.value()).matches();
        }
        if (x instanceof Segment.RegexSeg r && y instanceof Segment.StaticSeg s) {
            return r.regex().matcher(s.value()).matches();
        }
        return true; // param/regex pairs are conservatively considered overlapping
    }

    @Override public String toString() { return raw; }
}
