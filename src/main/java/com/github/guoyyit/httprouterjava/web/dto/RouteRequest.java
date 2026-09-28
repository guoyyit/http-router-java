package com.github.guoyyit.httprouterjava.web.dto;

import com.github.guoyyit.httprouterjava.core.InvalidRouteException;
import com.github.guoyyit.httprouterjava.model.HeaderCondition;
import com.github.guoyyit.httprouterjava.model.HttpMethod;
import com.github.guoyyit.httprouterjava.model.Route;

import java.util.List;

/** Route create/update payload. */
public record RouteRequest(String handlerId, String method, String path,
                           List<HeaderCondition> headers, Integer priority) {

    public Route toRoute() {
        if (method == null || method.isBlank()) {
            throw new InvalidRouteException("method must not be blank");
        }
        try {
            return new Route(handlerId, HttpMethod.from(method), path,
                    headers == null ? List.of() : headers,
                    priority == null ? 0 : priority);
        } catch (IllegalArgumentException e) {
            throw new InvalidRouteException("unknown HTTP method: " + method);
        }
    }
}
