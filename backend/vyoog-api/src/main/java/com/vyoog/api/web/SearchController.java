package com.vyoog.api.web;

import com.vyoog.api.config.PrincipalGuard;
import com.vyoog.identity.UserProvisioningService;
import com.vyoog.search.SearchService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** VYB-0766 / VYB-0908: search, limited to what the caller's grants cover (see {@link SearchService}). */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService search;
    private final UserProvisioningService provisioning;
    private final PrincipalGuard guard;

    public SearchController(SearchService search, UserProvisioningService provisioning, PrincipalGuard guard) {
        this.search = search;
        this.provisioning = provisioning;
        this.guard = guard;
    }

    @GetMapping
    public List<SearchService.Result> search(@RequestParam String q, @AuthenticationPrincipal Jwt jwt) {
        guard.requireHuman(jwt); // a service account has no grants and nothing to search
        var user = provisioning.upsert(jwt.getSubject(), jwt.getClaimAsString("email"),
            jwt.getClaimAsString("preferred_username"));
        return search.search(q, user.getId());
    }
}
