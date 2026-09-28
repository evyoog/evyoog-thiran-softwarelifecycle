package com.vyoog.requirements;

import java.util.UUID;

/**
 * D12: where a requirement sits — at product, application or capability level, or
 * nowhere yet.
 *
 * <p>Spec §1.2 originally required every requirement to hang off exactly one capability.
 * That was relaxed on the product owner's instruction so that genuinely cross-cutting
 * rules ("every screen loads in under two seconds") can be stated once at the level they
 * actually apply to, rather than copied into a capability per application — which is what
 * people were doing, and which makes the duplicate detector fight the modelling.
 *
 * <p>What did <em>not</em> change is that a requirement has exactly one placement. This
 * type exists so that rule is stated once and cannot be half-applied: there is no way to
 * construct a placement naming both a product and a capability.
 */
public record Placement(PlacementLevel level, UUID productId, UUID applicationId, UUID capabilityId) {

    public static Placement unplaced() {
        return new Placement(PlacementLevel.UNPLACED, null, null, null);
    }

    public static Placement product(UUID productId) {
        return new Placement(PlacementLevel.PRODUCT, required(productId, "product"), null, null);
    }

    public static Placement application(UUID applicationId) {
        return new Placement(PlacementLevel.APPLICATION, null, required(applicationId, "application"), null);
    }

    public static Placement capability(UUID capabilityId) {
        return new Placement(PlacementLevel.CAPABILITY, null, null, required(capabilityId, "capability"));
    }

    /**
     * Builds a placement from whichever of the three ids a caller supplied.
     *
     * <p>Refuses more than one rather than silently preferring the narrowest. A request
     * naming both an application and a capability is not a request for the capability —
     * it is a caller who does not know what they are asking for, and guessing on their
     * behalf would put the requirement somewhere they did not choose.
     */
    public static Placement of(UUID productId, UUID applicationId, UUID capabilityId) {
        int given = (productId != null ? 1 : 0) + (applicationId != null ? 1 : 0) + (capabilityId != null ? 1 : 0);
        if (given > 1) {
            throw new IllegalArgumentException(
                "A requirement sits at one level — name a product, an application or a capability, not several.");
        }
        if (capabilityId != null) return capability(capabilityId);
        if (applicationId != null) return application(applicationId);
        if (productId != null) return product(productId);
        return unplaced();
    }

    /** The pre-D12 contract, where a null capability meant "unplaced". */
    public static Placement capabilityOrUnplaced(UUID capabilityId) {
        return capabilityId == null ? unplaced() : capability(capabilityId);
    }

    private static UUID required(UUID id, String what) {
        if (id == null) throw new IllegalArgumentException("A " + what + "-level requirement needs a " + what + ".");
        return id;
    }

    /** The one id that is set, or null when unplaced — for audit and revision snapshots. */
    public UUID id() {
        return switch (level) {
            case PRODUCT -> productId;
            case APPLICATION -> applicationId;
            case CAPABILITY -> capabilityId;
            case UNPLACED -> null;
        };
    }

    public boolean isPlaced() {
        return level != PlacementLevel.UNPLACED;
    }
}
