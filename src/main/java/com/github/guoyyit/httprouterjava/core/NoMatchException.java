package com.github.guoyyit.httprouterjava.core;

/**
 * Raised when no route can serve the request (distinct 404-style miss).
 */
public class NoMatchException extends RouterException {
    public NoMatchException(String message) {
        super(message);
    }
}
