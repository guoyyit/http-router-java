package com.github.guoyyit.httprouterjava.web.dto;

import com.github.guoyyit.httprouterjava.model.Route;

import java.util.List;

/** Atomic full-table replacement payload. */
public record RoutesReplaceRequest(List<RouteRequest> routes) {

    public List<Route> toRoutes() {
        if (routes == null) {
            return List.of();
        }
        return routes.stream().map(RouteRequest::toRoute).toList();
    }
}
