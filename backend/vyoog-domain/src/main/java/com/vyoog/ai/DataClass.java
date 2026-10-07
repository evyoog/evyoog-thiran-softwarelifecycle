package com.vyoog.ai;

/**
 * VYB-0937 (F27): a kind of sensitive text that is taken out of what is sent to a model provider.
 *
 * <p>{@link #SECRET} is removed and never comes back (a credential has no business in a reply, and nothing
 * restores it); it cannot be opted out. The others are replaced by a token such as {@code [EMAIL_1]} and put back
 * in the provider's reply, and an administrator may switch each one off.
 */
public enum DataClass {
    SECRET(false, "SECRET"),
    CARD(true, "CARD"),
    EMAIL(true, "EMAIL"),
    IP_ADDRESS(true, "IP"),
    PHONE(true, "PHONE"),
    /** The display names of the people in this system's own user table, matched exactly. Not a name-recognition model. */
    PERSON(true, "PERSON");

    private final boolean optOutAllowed;
    private final String tokenName;

    DataClass(boolean optOutAllowed, String tokenName) {
        this.optOutAllowed = optOutAllowed;
        this.tokenName = tokenName;
    }

    public boolean optOutAllowed() {
        return optOutAllowed;
    }

    /** The word inside a token: {@code [EMAIL_1]}. */
    public String tokenName() {
        return tokenName;
    }
}
