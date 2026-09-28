package com.github.guoyyit.httprouterjava.core;

/**
 * Base class for all router failures. Callers can distinguish miss vs conflict
 * vs bad configuration via the concrete subclass.
 */
public class RouterException extends RuntimeException {
    public RouterException(String message) {
        super(message);
    }

    public RouterException(String message, Throwable cause) {
        super(message, cause);
    }
}
