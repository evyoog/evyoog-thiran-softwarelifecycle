package com.vyoog.identity;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * VYB-0305/0311/0901: is this caller a service account rather than a person.
 *
 * <p>A service account is a <em>registered</em> one: the token's {@code azp} (authorized
 * party) claim must match a {@link ServiceAccount#getClientId()} (current or, during a
 * rotation's overlap window, {@code previousClientId} — VYB-0712 AC1). Nothing else
 * counts. An earlier version also treated any token with a blank {@code email} as a
 * service account, on the theory that a client-credentials grant never carries one; that
 * let <em>any</em> email-less token from the realm (another client, a public client
 * without the email scope) use the CI ingestion endpoints without ever being registered.
 *
 * <p>The inverse question — "is this a person" — is {@link #isPerson}: not a registered
 * service account, and carrying the {@code email} claim every human sign-in has (VYB-0009
 * upserts one on first sight). A token that is neither is refused by both guards.
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

    /** VYB-0901: registered accounts only — a blank {@code email} does not make a token a service account. */
    public boolean isServiceAccount(String azpClaim) {
        return resolve(azpClaim).isPresent();
    }

    /** A person: not a registered service account, and carrying an {@code email} claim. */
    public boolean isPerson(String azpClaim, String emailClaim) {
        return !isServiceAccount(azpClaim) && emailClaim != null && !emailClaim.isBlank();
    }

    /** VYB-0711: an account with no scopes can do nothing — a claim that doesn't resolve to any account has none either. */
    public boolean hasScope(String azpClaim, String requiredScope) {
        return resolve(azpClaim).map(a -> a.getScopes().contains(requiredScope)).orElse(false);
    }
}
