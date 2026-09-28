package com.vyoog.identity;

/** VYB-0311: this endpoint accepts only a service account (a CI system), not a person. */
public class ServiceAccountRequiredException extends RuntimeException {
    public ServiceAccountRequiredException(String message) {
        super(message);
    }
}
