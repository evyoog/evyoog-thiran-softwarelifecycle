package com.vyoog.identity;

/** VYB-0701/0703: the caller's grants don't cover what they tried to do. */
public class GrantRequiredException extends RuntimeException {

    private final AccessRole role;

    public GrantRequiredException(String message, AccessRole role) {
        super(message);
        this.role = role;
    }

    public AccessRole getRole() { return role; }
}
