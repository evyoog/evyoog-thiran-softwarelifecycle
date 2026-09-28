package com.vyoog.requirements;

/**
 * D12. Ordered widest-first, which is the order the levels are offered in and the order
 * they read in a breadcrumb — not a ranking of importance.
 */
public enum PlacementLevel {
    PRODUCT,
    APPLICATION,
    CAPABILITY,
    /** Created but not yet placed — the state every import candidate starts in. */
    UNPLACED
}
