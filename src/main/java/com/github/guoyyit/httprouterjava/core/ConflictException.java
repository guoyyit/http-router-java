package com.github.guoyyit.httprouterjava.core;

import java.util.List;

/**
 * Raised when several routes match a request with no unique winner,
 * or when a registration is rejected due to an unresolved overlap.
 */
public class ConflictException extends RouterException {
    private final transient List<String> conflictingHandlers;

    public ConflictException(String message, List<String> conflictingHandlers) {
        super(message);
        this.conflictingHandlers = List.copyOf(conflictingHandlers);
    }

    public List<String> getConflictingHandlers() {
        return conflictingHandlers;
    }
}
