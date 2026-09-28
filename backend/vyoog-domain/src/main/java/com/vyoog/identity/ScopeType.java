package com.vyoog.identity;

/**
 * What an {@link AccessGrant} covers. PLATFORM has no {@code scopeId} (the database
 * CHECK constraint enforces the same rule this enum's callers must respect);
 * everything else narrows to one product/application/capability/release.
 */
public enum ScopeType {
    PLATFORM, PRODUCT, APP, CAPABILITY, RELEASE
}
