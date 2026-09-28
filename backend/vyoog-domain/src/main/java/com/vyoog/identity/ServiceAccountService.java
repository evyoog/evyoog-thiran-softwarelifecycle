package com.vyoog.identity;

import com.vyoog.platform.audit.AuditService;
import com.vyoog.platform.config.AppConfigService;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** VYB-0710–0713. */
@Service
public class ServiceAccountService {

    private final ServiceAccountRepository accounts;
    private final AppConfigService config;
    private final AuditService audit;

    public ServiceAccountService(ServiceAccountRepository accounts, AppConfigService config, AuditService audit) {
        this.accounts = accounts;
        this.config = config;
        this.audit = audit;
    }

    /** VYB-0711 AC1/AC2: explicit scopes required, each checked against the known set — nothing implicit, nothing unrecognised. */
    @Transactional
    public ServiceAccount create(String name, String purpose, String clientId, List<String> scopes, UUID actor) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("A service account needs at least one explicit scope");
        }
        for (String scope : scopes) {
            if (!KnownServiceScopes.ALL.contains(scope)) {
                throw new IllegalArgumentException("Unknown scope: " + scope);
            }
        }
        ServiceAccount account = accounts.save(new ServiceAccount(name, purpose, clientId, scopes));
        audit.record(actor, "service-account.created", "SERVICE_ACCOUNT", account.getId(), null,
            Map.of("name", name, "scopes", scopes));
        return account;
    }

    /**
     * VYB-0712: records a rotation. The new {@code clientId} itself is not minted
     * here — that's a Keycloak admin action this deployment has never exercised (see
     * BUILD-REGISTER.md) — this call is what a rotation flow would invoke once that
     * new client id exists, to make the switchover and its overlap window real in
     * this system's own records. AC2: audited.
     */
    @Transactional
    public ServiceAccount rotate(UUID accountId, String newClientId, UUID actor) {
        ServiceAccount account = accounts.findById(accountId).orElseThrow(NoSuchElementException::new);
        String oldClientId = account.getClientId();
        account.rotate(newClientId, config.keyRotationOverlapDays());
        accounts.save(account);
        audit.record(actor, "service-account.key-rotated", "SERVICE_ACCOUNT", accountId,
            Map.of("clientId", oldClientId), Map.of("clientId", newClientId));
        return account;
    }

    public List<ServiceAccount> all() {
        return accounts.findAll();
    }
}
