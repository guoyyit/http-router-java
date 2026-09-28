package com.github.guoyyit.httprouterjava.router;

/** Thrown when a route pattern string is syntactically invalid. */
public class PatternParseException extends RuntimeException {
    public PatternParseException(String message) { super(message); }
}
