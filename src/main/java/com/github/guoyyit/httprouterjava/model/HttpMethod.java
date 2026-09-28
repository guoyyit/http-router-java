package com.github.guoyyit.httprouterjava.model;

/**
 * HTTP methods supported by the router.
 */
public enum HttpMethod {
    GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH, TRACE;

    public static HttpMethod from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        return HttpMethod.valueOf(raw.trim().toUpperCase());
    }
}
