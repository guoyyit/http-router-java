package com.github.guoyyit.httprouterjava.model;

import java.util.Objects;

/**
 * A single request-header condition attached to a route.
 */
public final class HeaderCondition {
    private final String name;
    private final HeaderMatchType type;
    private final String value;
    public HeaderCondition() {
        this.name = null;
        this.type = null;
        this.value = null;
    }

    public HeaderCondition(String name, HeaderMatchType type, String value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }

    public String getName() { return name; }
    public HeaderMatchType getType() { return type; }
    public String getValue() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeaderCondition that)) return false;
        return Objects.equals(name, that.name) && type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, value);
    }
}
