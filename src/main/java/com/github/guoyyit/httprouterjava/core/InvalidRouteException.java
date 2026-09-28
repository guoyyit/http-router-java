package com.github.guoyyit.httprouterjava.core;

/**
 * Raised when a route definition itself is malformed (bad pattern, bad header
 * condition, missing fields, etc.).
 */
public class InvalidRouteException extends RouterException {
    public InvalidRouteException(String message) {
        super(message);
    }

    public InvalidRouteException(String message, Throwable cause) {
        super(message, cause);
    }
}
