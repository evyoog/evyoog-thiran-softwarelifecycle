package com.vyoog.identity;

import java.util.Objects;

/** One point in a scope hierarchy — {@code scopeId} is null only for PLATFORM. */
public record ScopeRef(ScopeType type, java.util.UUID id) {

    public static ScopeRef platform() {
        return new ScopeRef(ScopeType.PLATFORM, null);
    }

    public boolean matches(ScopeType type, java.util.UUID id) {
        return this.type == type && Objects.equals(this.id, id);
    }
}
