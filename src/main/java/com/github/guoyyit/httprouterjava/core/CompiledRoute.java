package com.github.guoyyit.httprouterjava.core;

import com.github.guoyyit.httprouterjava.model.HeaderCondition;
import com.github.guoyyit.httprouterjava.model.HeaderMatchType;
import com.github.guoyyit.httprouterjava.model.Route;
import com.github.guoyyit.httprouterjava.pattern.PathPattern;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A route together with its pre-parsed path pattern and pre-compiled header
 * regexes. Route tables are rebuilt on every commit, so lookup work stays
 * allocation-light and free of parsing.
 */
public final class CompiledRoute {

    private final Route route;
    private final PathPattern pattern;

    public CompiledRoute(Route route, PathPattern pattern) {
        this.route = route;
        this.pattern = pattern;
    }

    public Route getRoute() {
        return route;
    }

    public PathPattern getPattern() {
        return pattern;
    }

    public List<HeaderCondition> getHeaders() {
        return route.getHeaders();
    }

    /**
     * @return true if every header condition is satisfied by the request
     *         headers. Header names are case-insensitive.
     */
    public boolean matchesHeaders(Map<String, String> requestHeaders) {
        for (HeaderCondition condition : route.getHeaders()) {
            if (!matchesOne(condition, requestHeaders)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesOne(HeaderCondition condition,
                                      Map<String, String> requestHeaders) {
        String actual = lookup(requestHeaders, condition.getName());
        HeaderMatchType type = condition.getType();
        return switch (type) {
            case PRESENT -> actual != null;
            case ABSENT -> actual == null;
            case EXACT -> actual != null && actual.equalsIgnoreCase(condition.getValue());
            case REGEX -> actual != null && compile(condition).matcher(actual).matches();
        };
    }

    private static String lookup(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null || headers.containsKey(name)) {
            return direct;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Pattern compile(HeaderCondition condition) {
        try {
            return Pattern.compile(condition.getValue());
        } catch (PatternSyntaxException e) {
            throw new InvalidRouteException(
                    "invalid header regex '" + condition.getValue() + "'", e);
        }
    }
}
