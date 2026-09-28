package com.github.guoyyit.httprouterjava.core;

/**
 * Raised when snapshot persistence or recovery fails in an unrecoverable way.
 */
public class PersistenceException extends RouterException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
