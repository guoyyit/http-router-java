package com.github.guoyyit.httprouterjava.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A registered route: HTTP method + path pattern + optional header conditions
 * mapped to a handler identifier.
 *
 * @param priority larger value wins; equal-priority overlapping routes are rejected
 */
public final class Route {
    private String handlerId;
    private HttpMethod method;
    private String path;
    private List<HeaderCondition> headers = new ArrayList<>();
    private int priority;

    public Route() {
    }

    public Route(String handlerId, HttpMethod method, String path,
                 List<HeaderCondition> headers, int priority) {
        this.handlerId = handlerId;
        this.method = method;
        this.path = path;
        this.headers = headers == null ? new ArrayList<>() : new ArrayList<>(headers);
        this.priority = priority;
    }

    public String getHandlerId() { return handlerId; }
    public void setHandlerId(String handlerId) { this.handlerId = handlerId; }

    public HttpMethod getMethod() { return method; }
    public void setMethod(HttpMethod method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<HeaderCondition> getHeaders() { return headers; }
    public void setHeaders(List<HeaderCondition> headers) {
        this.headers = headers == null ? new ArrayList<>() : headers;
    }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Route route)) return false;
        return priority == route.priority
                && Objects.equals(handlerId, route.handlerId)
                && method == route.method
                && Objects.equals(path, route.path)
                && Objects.equals(headers, route.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handlerId, method, path, headers, priority);
    }
}
