package com.github.guoyyit.httprouterjava.model;

import java.util.Map;

/**
 * Result of a successful match: the winning handler and extracted path variables.
 */
public record MatchResult(String handlerId, int priority, Map<String, String> pathVariables,
                          Map<String, String> matchedHeaders) {
}
