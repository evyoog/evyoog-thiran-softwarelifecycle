package com.vyoog.identity;

/** VYB-0305: approval, sign-off and administration are refused to a service account. */
public class ServiceAccountRefusedException extends RuntimeException {
    public ServiceAccountRefusedException(String message) {
        super(message);
    }
}
