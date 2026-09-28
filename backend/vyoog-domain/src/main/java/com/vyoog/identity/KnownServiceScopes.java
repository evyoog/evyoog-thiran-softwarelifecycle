package com.vyoog.identity;

import java.util.Set;

/**
 * VYB-0711 AC2: the fixed, known set a service account's scopes are validated
 * against — one per integration this codebase actually has a service-account-only
 * endpoint for, plus a webhook-write scope per Phase 5's inbound integrations.
 */
public final class KnownServiceScopes {

    public static final String CI_INGEST = "ci:ingest";
    public static final String WEBHOOK_GIT = "webhook:git";
    public static final String WEBHOOK_CI = "webhook:ci";
    public static final String WEBHOOK_HR = "webhook:hr";
    public static final String WEBHOOK_PLANNING = "webhook:planning";

    public static final Set<String> ALL = Set.of(CI_INGEST, WEBHOOK_GIT, WEBHOOK_CI, WEBHOOK_HR, WEBHOOK_PLANNING);

    private KnownServiceScopes() {}
}
