package com.github.guoyyit.httprouterjava.router;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A condition on one request header. Header names are case-insensitive.
 * Value syntax: "v" exact, "!v" negated exact (absent header satisfies it),
 * "*" header must exist, "~regex" full regex match.
 */
public record HeaderCondition(String name, Kind kind, String value) {

    public enum Kind { EXISTS, EQUALS, NOT_EQUALS, REGEX }

    public HeaderCondition {
        if (name == null || name.isBlank()) {
            throw new PatternParseException("Header name must not be blank");
        }
        name = name.toLowerCase();
    }

    public static HeaderCondition parse(String name, String expression) {
        if (expression == null || expression.isEmpty()) {
            throw new PatternParseException("Header condition for '" + name + "' must not be empty");
        }
        if (expression.equals("*")) {
            return new HeaderCondition(name, Kind.EXISTS, "*");
        }
        if (expression.startsWith("!")) {
            String v = expression.substring(1);
            if (v.isEmpty()) {
                throw new PatternParseException("Empty negated header value for '" + name + "'");
            }
            return new HeaderCondition(name, Kind.NOT_EQUALS, v);
        }
        if (expression.startsWith("~")) {
            String regex = expression.substring(1);
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new PatternParseException("Invalid header regex for '" + name + "': " + e.getMessage());
            }
            return new HeaderCondition(name, Kind.REGEX, regex);
        }
        return new HeaderCondition(name, Kind.EQUALS, expression);
    }

    public boolean matches(Map<String, String> requestHeaders) {
        String actual = lookup(requestHeaders);
        return switch (kind) {
            case EXISTS -> actual != null;
            case EQUALS -> value.equals(actual);
            case NOT_EQUALS -> actual == null || !value.equals(actual);
            case REGEX -> actual != null && Pattern.compile(value).matcher(actual).matches();
        };
    }

    private String lookup(Map<String, String> headers) {
        String v = headers.get(name);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Two conditions on the same header contradict if no request can satisfy
     * both. Conservative: regex pairs are never reported as contradicting.
     */
    public boolean contradicts(HeaderCondition other) {
        if (!name.equalsIgnoreCase(other.name)) {
            return false;
        }
        return switch (kind) {
            case EXISTS -> false;
            case EQUALS -> switch (other.kind) {
                case EQUALS -> !value.equals(other.value);
                case NOT_EQUALS -> value.equals(other.value);
                case REGEX -> !Pattern.compile(other.value).matcher(value).matches();
                case EXISTS -> false;
            };
            case NOT_EQUALS -> other.kind == Kind.EQUALS && value.equals(other.value);
            case REGEX -> other.kind == Kind.EQUALS && !Pattern.compile(value).matcher(other.value).matches();
        };
    }

    /** Serialized form used in APIs and persistence, e.g. "!prod", "~v\\d+", "*", "blue". */
    public String expression() {
        return switch (kind) {
            case EXISTS -> "*";
            case EQUALS -> value;
            case NOT_EQUALS -> "!" + value;
            case REGEX -> "~" + value;
        };
    }
}
