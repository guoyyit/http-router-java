package com.github.guoyyit.httprouterjava.core;

import java.util.List;

/**
 * Raised when the path matches one or more routes but none accepts the
 * request method (distinct 405-style miss).
 */
public class MethodNotAllowedException extends RouterException {
    private final transient List<String> allowedMethods;

    public MethodNotAllowedException(String message, List<String> allowedMethods) {
        super(message);
        this.allowedMethods = List.copyOf(allowedMethods);
    }

    public List<String> getAllowedMethods() {
        return allowedMethods;
    }
}
