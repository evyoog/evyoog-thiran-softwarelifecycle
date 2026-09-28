package com.vyoog.integration;

/** VYB-0741 AC1: an unsigned or wrongly-signed payload is refused, never processed "just in case." */
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException(String message) { super(message); }
}
