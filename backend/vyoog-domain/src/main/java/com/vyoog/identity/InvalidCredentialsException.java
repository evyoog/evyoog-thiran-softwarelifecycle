package com.vyoog.identity;

/** VYB-0048b: Keycloak rejected a username/password grant — wrong credentials or a disabled account. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
