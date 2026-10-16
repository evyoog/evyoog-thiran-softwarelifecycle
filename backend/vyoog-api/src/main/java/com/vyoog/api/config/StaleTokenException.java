package com.vyoog.api.config;

/** VYB-0921 (D54): the token is too old (or has no usable issue time) for the endpoint that checks its age; answered 401. */
public class StaleTokenException extends RuntimeException {
    public StaleTokenException(String message) {
        super(message);
    }
}
