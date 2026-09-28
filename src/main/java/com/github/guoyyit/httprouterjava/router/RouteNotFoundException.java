package com.github.guoyyit.httprouterjava.router;

/** Thrown when deleting a route that does not exist. */
public class RouteNotFoundException extends RuntimeException {
    public RouteNotFoundException(String message) { super(message); }
}
