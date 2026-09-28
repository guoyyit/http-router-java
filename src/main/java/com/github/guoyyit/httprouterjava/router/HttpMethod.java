package com.github.guoyyit.httprouterjava.router;

/** HTTP methods supported by the router; ANY matches every method. */
public enum HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, ANY;

    public boolean matches(HttpMethod requestMethod) {
        return this == ANY || this == requestMethod;
    }

    public static HttpMethod parse(String s) {
        if (s == null || s.isBlank()) {
            throw new PatternParseException("HTTP method must not be blank");
        }
        String v = s.trim().toUpperCase();
        return switch (v) {
            case "*", "ANY" -> ANY;
            default -> {
                try {
                    yield HttpMethod.valueOf(v);
                } catch (IllegalArgumentException e) {
                    throw new PatternParseException("Unknown HTTP method: " + s);
                }
            }
        };
    }
}
