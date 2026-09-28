package com.vyoog.identity;

import org.springframework.stereotype.Service;

/**
 * VYB-0303: signing requires a stronger authentication level than merely being
 * signed in — checked at the moment of signing, not once at login, because a session
 * opened this morning shouldn't still authorize a signature this afternoon on the
 * strength of a login that never asked for step-up at all.
 *
 * <p>The comparison itself is a plain string match against Keycloak's {@code acr}
 * claim — this deployment's realm has no step-up (conditional ACR) authentication
 * flow configured, so {@code assertAchieved} has never been exercised against a real
 * elevated token, only against its absence (see BUILD-REGISTER.md).
 */
@Service
public class StepUpChecker {

    public void assertAchieved(String achievedAcr, String requiredLevel) {
        if (!requiredLevel.equals(achievedAcr)) {
            throw new StepUpRequiredException(requiredLevel);
        }
    }
}
