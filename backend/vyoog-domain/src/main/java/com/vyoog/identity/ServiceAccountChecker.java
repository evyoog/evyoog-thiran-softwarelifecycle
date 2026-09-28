package com.vyoog.identity;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * VYB-0305/0311: is this caller a service account rather than a person. The token
 * itself never says so directly — there's no single Keycloak claim that reliably
 * means "this is a client-credentials token" across every possible realm
 * configuration — so this combines two signals: the {@code azp} (authorized party)
 * claim matching a registered {@link ServiceAccount#getClientId()} (current or, during
 * a rotation's overlap window, {@code previousClientId} — VYB-0712 AC1), or the caller
 * simply having no {@code email} claim, which every human sign-in carries (VYB-0009
 * upserts one on first sight) and a client-credentials grant never does.
 *
 * <p><strong>Unverified against a real client-credentials token</strong> — this
 * realm's actual client-credentials response has never been inspected in this
 * environment (see BUILD-REGISTER.md). If eVyoog's client-credentials tokens turn
 * out to carry an {@code email} claim after all, the first signal below is what
 * actually protects VYB-0305; the second would then need reconsidering.
 */
@Service
public class ServiceAccountChecker {

    private final ServiceAccountRepository accounts;

    public ServiceAccountChecker(ServiceAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** The registered account this claim resolves to, honouring a rotation's overlap window. */
    public Optional<ServiceAccount> resolve(String azpClaim) {
        if (azpClaim == null) return Optional.empty();
        Optional<ServiceAccount> current = accounts.findByClientId(azpClaim);
        if (current.isPresent()) return current;
        return accounts.findAllByPreviousClientId(azpClaim).stream()
            .filter(a -> a.previousKeyStillValid(azpClaim))
            .findFirst();
    }

    public boolean isServiceAccount(String azpClaim, String emailClaim) {
        if (resolve(azpClaim).isPresent()) return true;
        return emailClaim == null || emailClaim.isBlank();
    }

    /** VYB-0711: an account with no scopes can do nothing — a claim that doesn't resolve to any account has none either. */
    public boolean hasScope(String azpClaim, String requiredScope) {
        return resolve(azpClaim).map(a -> a.getScopes().contains(requiredScope)).orElse(false);
    }
}
