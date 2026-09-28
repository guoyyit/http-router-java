package com.github.guoyyit.httprouterjava.router;

/** Thrown when a route update is rejected because it duplicates or conflicts with existing routes. */
public class RouteConflictException extends RuntimeException {
    public RouteConflictException(String message) { super(message); }
}
