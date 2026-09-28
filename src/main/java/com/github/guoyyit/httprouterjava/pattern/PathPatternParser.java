package com.github.guoyyit.httprouterjava.pattern;

import com.github.guoyyit.httprouterjava.core.InvalidRouteException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses route path strings into {@link PathPattern}s, expanding optional
 * fragments and validating parameter / regex syntax.
 *
 * <p>Grammar (per route path, must start with {@code /}):</p>
 * <ul>
 *   <li>{@code /users}            &rarr; static segment</li>
 *   <li>{@code /users/{id}}       &rarr; parameter segment (one path segment)</li>
 *   <li>{@code /users/{id:[0-9]+}} &rarr; parameter with a full-match regex</li>
 *   <li>{@code /assets/*}         &rarr; single-segment wildcard</li>
 *   <li>{@code /assets/**}        &rarr; trailing catch-all (zero+ segments),
 *       optional name via {@code **:name}</li>
 *   <li>{@code /api(/v1)/users}   &rarr; parenthesised optional fragment,
 *       expanded to two concrete patterns; no nesting, at most 10 groups</li>
 * </ul>
 */
@Component
public class PathPatternParser {

    /** Bound on expanded alternatives: each optional group doubles the set. */
    public static final int MAX_OPTIONAL_GROUPS = 10;

    private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public PathPatternParser() {
    }

    public PathPattern parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRouteException("path must not be blank");
        }
        String path = raw.trim();
        if (!path.startsWith("/")) {
            throw new InvalidRouteException("path must start with '/': " + raw);
        }
        if (path.contains("?") || path.contains("#")) {
            throw new InvalidRouteException("path must not contain query or fragment: " + raw);
        }

        List<String> expanded = expandOptionalGroups(path, raw);
        List<List<Segment>> alternatives = new ArrayList<>(expanded.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String concrete : expanded) {
            if (!seen.add(concrete)) {
                continue; // e.g. "/a()" duplicate expansion
            }
            List<Segment> segments = parseConcrete(concrete, raw);
            validateSegments(segments, raw);
            alternatives.add(List.copyOf(segments));
        }
        return new PathPattern(raw, List.copyOf(alternatives));
    }

    /** Splits a request path into normalized, non-empty segments. */
    public static List<String> splitRequestPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("request path must not be blank");
        }
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (!p.startsWith("/")) {
            throw new IllegalArgumentException("request path must start with '/': " + path);
        }
        List<String> out = new ArrayList<>();
        for (String part : p.substring(1).split("/", -1)) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private List<String> expandOptionalGroups(String path, String raw) {
        List<String> alternatives = new ArrayList<>();
        alternatives.add("");
        int groups = 0;
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == ')') {
                throw new InvalidRouteException("unmatched ')' in path: " + raw);
            }
            if (c != '(') {
                int next = path.indexOf('(', i);
                int close = path.indexOf(')', i);
                int endLiteral = next < 0 ? path.length() : next;
                if (close >= 0 && close < endLiteral) {
                    throw new InvalidRouteException("unmatched ')' in path: " + raw);
                }
                String literal = path.substring(i, endLiteral);
                for (int a = 0; a < alternatives.size(); a++) {
                    alternatives.set(a, alternatives.get(a) + literal);
                }
                i = endLiteral;
                continue;
            }
            // c == '('
            int depth = 1;
            int j = i + 1;
            while (j < path.length() && depth > 0) {
                char gc = path.charAt(j);
                if (gc == '(') {
                    throw new InvalidRouteException(
                            "nested optional groups are not allowed: " + raw);
                }
                if (gc == ')') {
                    depth--;
                }
                j++;
            }
            if (depth != 0) {
                throw new InvalidRouteException("unmatched '(' in path: " + raw);
            }
            groups++;
            if (groups > MAX_OPTIONAL_GROUPS) {
                throw new InvalidRouteException(
                        "too many optional groups (>" + MAX_OPTIONAL_GROUPS + "): " + raw);
            }
            String inside = path.substring(i + 1, j - 1);
            if (inside.isEmpty()) {
                throw new InvalidRouteException("empty optional group in path: " + raw);
            }
            List<String> forked = new ArrayList<>(alternatives.size() * 2);
            for (String base : alternatives) {
                forked.add(base);
                forked.add(base + inside);
            }
            alternatives = forked;
            i = j;
        }
        return alternatives;
    }

    private List<Segment> parseConcrete(String concrete, String raw) {
        List<Segment> segments = new ArrayList<>();
        boolean first = true;
        for (String token : concrete.substring(1).split("/", -1)) {
            if (first) {
                first = false;
            }
            if (token.isEmpty()) {
                throw new InvalidRouteException(
                        "empty path segment (double '/') in: " + raw);
            }
            segments.add(parseToken(token, raw));
        }
        return segments;
    }

    private Segment parseToken(String token, String raw) {
        if (token.equals("*")) {
            return new WildcardSegment();
        }
        if (token.equals("**")) {
            return new CatchAllSegment("**");
        }
        if (token.startsWith("**:")) {
            String name = token.substring(3);
            if (!PARAM_NAME.matcher(name).matches()) {
                throw new InvalidRouteException("illegal catch-all name in: " + raw);
            }
            return new CatchAllSegment(name);
        }
        if (token.indexOf('{') >= 0 || token.indexOf('}') >= 0) {
            return parseParamToken(token, raw);
        }
        if (token.indexOf('*') >= 0) {
            throw new InvalidRouteException(
                    "wildcard '*' must occupy a whole segment: " + raw);
        }
        return new LiteralSegment(token);
    }

    private Segment parseParamToken(String token, String raw) {
        if (token.length() < 3 || token.charAt(0) != '{'
                || token.charAt(token.length() - 1) != '}'
                || token.indexOf('{', 1) >= 0 || token.indexOf('}', 1) != token.length() - 1) {
            throw new InvalidRouteException(
                    "parameter must be the whole segment and be well-formed: " + raw);
        }
        String body = token.substring(1, token.length() - 1);
        int colon = body.indexOf(':');
        String name;
        Pattern constraint = null;
        if (colon < 0) {
            name = body;
        } else {
            name = body.substring(0, colon);
            String regex = body.substring(colon + 1);
            if (regex.isEmpty()) {
                throw new InvalidRouteException("empty regex constraint in: " + raw);
            }
            try {
                constraint = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new InvalidRouteException(
                        "invalid regex constraint in: " + raw + " (" + e.getMessage() + ")", e);
            }
        }
        if (!PARAM_NAME.matcher(name).matches()) {
            throw new InvalidRouteException("illegal parameter name '" + name + "' in: " + raw);
        }
        return new ParamSegment(name, constraint);
    }

    private void validateSegments(List<Segment> segments, String raw) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s instanceof CatchAllSegment c) {
                if (i != segments.size() - 1) {
                    throw new InvalidRouteException(
                            "'**' must be the last segment: " + raw);
                }
                if (!names.add(c.variable())) {
                    throw new InvalidRouteException(
                            "duplicate path variable '" + c.variable() + "' in: " + raw);
                }
            } else if (s instanceof ParamSegment p) {
                if (!names.add(p.name())) {
                    throw new InvalidRouteException(
                            "duplicate path variable '" + p.name() + "' in: " + raw);
                }
            }
        }
    }
}
