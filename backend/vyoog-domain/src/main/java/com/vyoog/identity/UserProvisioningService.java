package com.vyoog.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the local mirror row the first time a Keycloak subject is seen, and keeps
 * the mutable attributes fresh on subsequent sign-ins.
 */
@Service
public class UserProvisioningService {

    private final AppUserRepository users;

    public UserProvisioningService(AppUserRepository users) {
        this.users = users;
    }

    /**
     * VYB-0700: a subject seen before updates its own row; a subject seen for the first
     * time claims the placeholder row an administrator created for that email, if there
     * is one, and otherwise gets a fresh row.
     *
     * <p>The claim step is what makes administrator-created users worth anything: grants,
     * a manager and a delegate assigned before someone's first sign-in have to survive
     * that sign-in. Without it this method would match on subject, find nothing, and
     * create a second row — leaving the prepared one orphaned and the person with none of
     * the access that was set up for them.
     *
     * <p>Only a row still carrying {@link AppUserService#UNCLAIMED_SUBJECT_PREFIX} can be
     * claimed. A row with a real subject is never adopted by an email match, however
     * exactly the address agrees: that path would hand one person another person's
     * grants on an IdP email collision, which is an account takeover rather than a
     * convenience.
     */
    @Transactional
    public AppUser upsert(String subject, String email, String displayName) {
        AppUser user = users.findBySubject(subject)
                .or(() -> claimable(email))
                .orElseGet(() -> new AppUser(subject, email, displayName));
        if (user.getSubject().startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX)) {
            user.claimSubject(subject);
        }
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.touch();
        return users.save(user);
    }

    /**
     * The one prepared row for this email, if there is exactly one.
     *
     * <p>Two unclaimed rows on the same address is a directory an administrator can now
     * create deliberately, so this has to answer for that case rather than assume it
     * away. It claims neither: picking one would hand this person whichever set of roles
     * happened to sort first, and a silently arbitrary grant is worse than no grant. They
     * get a fresh row, and the prepared ones stay visible in the directory as the mess
     * they are.
     */
    private Optional<AppUser> claimable(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        List<AppUser> unclaimed = users.findAllByEmailIgnoreCase(email.strip()).stream()
            .filter(u -> u.getSubject().startsWith(AppUserService.UNCLAIMED_SUBJECT_PREFIX))
            .toList();
        return unclaimed.size() == 1 ? Optional.of(unclaimed.get(0)) : Optional.empty();
    }
}
