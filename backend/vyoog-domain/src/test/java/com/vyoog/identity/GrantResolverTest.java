package com.vyoog.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** VYB-0701 AC1/AC2: the scope-hierarchy walk and its "stop at the first match" resolution. */
@ExtendWith(MockitoExtension.class)
class GrantResolverTest {

    @Mock AccessGrantRepository grants;
    @Mock CapabilityRepository capabilities;
    @Mock ApplicationRepository applications;

    GrantResolver resolver;

    UUID userId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID appId = UUID.randomUUID();
    UUID capId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new GrantResolver(grants, capabilities, applications);
    }

    @Test
    void ancestorChainForCapabilityReachesProductAndPlatform() {
        Application app = new Application(productId, "App");
        Capability cap = new Capability(appId, "Cap");
        when(capabilities.findById(capId)).thenReturn(java.util.Optional.of(cap));
        when(applications.findById(appId)).thenReturn(java.util.Optional.of(app));

        List<ScopeRef> chain = resolver.ancestorChain(ScopeType.CAPABILITY, capId);

        assertThat(chain).containsExactly(
            new ScopeRef(ScopeType.CAPABILITY, capId),
            new ScopeRef(ScopeType.APP, appId),
            new ScopeRef(ScopeType.PRODUCT, productId),
            ScopeRef.platform());
    }

    @Test
    void releaseHasNoParentOtherThanPlatform() {
        UUID releaseId = UUID.randomUUID();
        assertThat(resolver.ancestorChain(ScopeType.RELEASE, releaseId))
            .containsExactly(new ScopeRef(ScopeType.RELEASE, releaseId), ScopeRef.platform());
    }

    @Test
    void aProductLevelGrantIsEffectiveAtACapabilityBeneathIt() {
        Application app = new Application(productId, "App");
        Capability cap = new Capability(appId, "Cap");
        when(capabilities.findById(capId)).thenReturn(java.util.Optional.of(cap));
        when(applications.findById(appId)).thenReturn(java.util.Optional.of(app));
        when(grants.findAllByUserId(userId)).thenReturn(List.of(
            new AccessGrant(userId, AccessRole.APPROVER, ScopeType.PRODUCT, productId, null, null)));

        assertThat(resolver.hasEffectiveRole(userId, AccessRole.APPROVER, ScopeType.CAPABILITY, capId)).isTrue();
    }

    @Test
    void aGrantOnADifferentProductDoesNotReachThisCapability() {
        UUID otherProductId = UUID.randomUUID();
        Application app = new Application(productId, "App");
        Capability cap = new Capability(appId, "Cap");
        when(capabilities.findById(capId)).thenReturn(java.util.Optional.of(cap));
        when(applications.findById(appId)).thenReturn(java.util.Optional.of(app));
        when(grants.findAllByUserId(userId)).thenReturn(List.of(
            new AccessGrant(userId, AccessRole.APPROVER, ScopeType.PRODUCT, otherProductId, null, null)));

        assertThat(resolver.hasEffectiveRole(userId, AccessRole.APPROVER, ScopeType.CAPABILITY, capId)).isFalse();
    }

    @Test
    void anExpiredGrantConfersNothing() {
        when(grants.findAllByUserId(userId)).thenReturn(List.of(
            new AccessGrant(userId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null,
                Instant.now().minusSeconds(60))));

        assertThat(resolver.isPlatformAdministrator(userId)).isFalse();
    }

    @Test
    void aRevokedGrantConfersNothing() {
        AccessGrant grant = new AccessGrant(userId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, null);
        grant.revoke();
        when(grants.findAllByUserId(userId)).thenReturn(List.of(grant));

        assertThat(resolver.isPlatformAdministrator(userId)).isFalse();
    }

    @Test
    void aPlatformAdministratorGrantIsEffectiveEverywhere() {
        Application app = new Application(productId, "App");
        Capability cap = new Capability(appId, "Cap");
        when(capabilities.findById(capId)).thenReturn(java.util.Optional.of(cap));
        when(applications.findById(appId)).thenReturn(java.util.Optional.of(app));
        when(grants.findAllByUserId(userId)).thenReturn(List.of(
            new AccessGrant(userId, AccessRole.ADMINISTRATOR, ScopeType.PLATFORM, null, null, null)));

        assertThat(resolver.hasEffectiveRole(userId, AccessRole.ADMINISTRATOR, ScopeType.CAPABILITY, capId)).isTrue();
        assertThat(resolver.isPlatformAdministrator(userId)).isTrue();
    }
}
