package com.github.guoyyit.httprouterjava.model;

/**
 * How a single header condition is evaluated against a request header.
 */
public enum HeaderMatchType {
    /** Request header must exist and value must equal {@code value} (case-insensitive). */
    EXACT,
    /** Request header must exist (value unrestricted). */
    PRESENT,
    /** Request header must not exist. */
    ABSENT,
    /** Request header must exist and value must match the regex {@code value}. */
    REGEX
}
