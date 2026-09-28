package com.vyoog.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findBySubject(String subject);
    /**
     * A list, not an Optional: the directory deliberately permits two rows to share an
     * email (see {@code AppUserService#createLocal}), and Spring Data throws
     * IncorrectResultSizeDataAccessException rather than returning either one when a
     * single-result finder matches more than once. That exception on the sign-in path
     * would lock every affected person out of the product entirely.
     */
    java.util.List<AppUser> findAllByEmailIgnoreCase(String email);
    java.util.List<AppUser> findAllByDisplayNameIgnoreCase(String displayName);

    /** VYB-0706: who nominated this user as their delegate. */
    java.util.List<AppUser> findAllByDelegateId(UUID delegateId);

    /** VYB-0502: the picker's search — matches on name or email, either is a fine way to find a colleague. */
    java.util.List<AppUser> findAllByDisplayNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email);
}
