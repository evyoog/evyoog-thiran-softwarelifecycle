package com.vyoog.requirements;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D12: a requirement sits at exactly one level. Spec §1.2 used to guarantee that by only
 * having one column to put it in; now there are three, and this type is what keeps the
 * guarantee. Everything here is about the ways it could be quietly broken.
 */
class PlacementTest {

    private final UUID product = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();
    private final UUID capability = UUID.randomUUID();

    @Test
    void VYB0201_AC1_eachLevelSetsItsOwnIdAndLeavesTheOthersNull() {
        assertThat(Placement.product(product))
            .returns(PlacementLevel.PRODUCT, Placement::level)
            .returns(product, Placement::productId)
            .returns(null, Placement::applicationId)
            .returns(null, Placement::capabilityId);
        assertThat(Placement.capability(capability))
            .returns(capability, Placement::capabilityId)
            .returns(null, Placement::productId);
    }

    @Test
    void VYB0201_AC1_namingTwoLevelsIsRefusedRatherThanResolvedToTheNarrowest() {
        // Preferring the capability would look helpful and would put the requirement
        // somewhere the caller did not choose — the database CHECK would not even catch
        // it, because only one column would end up set.
        assertThatThrownBy(() -> Placement.of(product, null, capability))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("one level");
        assertThatThrownBy(() -> Placement.of(product, application, capability))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0201_AC1_namingNothingIsUnplacedRatherThanAnError() {
        // Every import candidate starts here, so this must stay legal.
        assertThat(Placement.of(null, null, null).level()).isEqualTo(PlacementLevel.UNPLACED);
        assertThat(Placement.unplaced().isPlaced()).isFalse();
        assertThat(Placement.unplaced().id()).isNull();
    }

    @Test
    void VYB0201_AC1_aLevelWithoutItsIdIsRefused() {
        assertThatThrownBy(() -> Placement.product(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Placement.application(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Placement.capability(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VYB0201_AC1_theOldNullCapabilityMeansUnplacedContractStillHolds() {
        // Every pre-D12 caller passed a possibly-null capability id and meant "unplaced"
        // by it. That must not start throwing.
        assertThat(Placement.capabilityOrUnplaced(null).level()).isEqualTo(PlacementLevel.UNPLACED);
        assertThat(Placement.capabilityOrUnplaced(capability).level()).isEqualTo(PlacementLevel.CAPABILITY);
    }

    @Test
    void VYB0201_AC1_idReturnsWhicheverOneIsSet() {
        assertThat(Placement.product(product).id()).isEqualTo(product);
        assertThat(Placement.application(application).id()).isEqualTo(application);
        assertThat(Placement.capability(capability).id()).isEqualTo(capability);
    }

    @Test
    void VYB0201_AC1_placingARequirementClearsTheLevelItCameFrom() {
        // The failure this prevents: moving a capability-level requirement up to product
        // level while leaving capability_id set, which violates the database CHECK and
        // would otherwise surface as an opaque constraint error at flush time.
        Requirement r = new Requirement("VY-1", "T", "S", UUID.randomUUID());
        r.initialize("FUNCTIONAL", "MEDIUM", Placement.capability(capability));
        assertThat(r.getPlacementLevel()).isEqualTo(PlacementLevel.CAPABILITY);

        r.place(Placement.product(product));

        assertThat(r.getCapabilityId()).isNull();
        assertThat(r.getApplicationId()).isNull();
        assertThat(r.getProductId()).isEqualTo(product);
        assertThat(r.getPlacementLevel()).isEqualTo(PlacementLevel.PRODUCT);
    }

    @Test
    void VYB0201_AC1_theLevelIsDerivedSoItCanNeverDisagreeWithTheColumns() {
        Requirement r = new Requirement("VY-2", "T", "S", UUID.randomUUID());
        assertThat(r.getPlacementLevel()).isEqualTo(PlacementLevel.UNPLACED);
        r.place(Placement.application(application));
        assertThat(r.getPlacement())
            .returns(PlacementLevel.APPLICATION, Placement::level)
            .returns(application, Placement::applicationId);
    }
}
