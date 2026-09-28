package com.github.guoyyit.httprouterjava.web.dto;

import java.util.Map;

/** Successful match response. */
public record MatchResponse(String handlerId, long version, int priority,
                            Map<String, String> pathVariables,
                            Map<String, String> matchedHeaders) {
}
