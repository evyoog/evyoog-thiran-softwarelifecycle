package com.vyoog.identity;

/** VYB-0901: the one-time bootstrap was refused — already done, an administrator already exists, or the caller is not authorised. */
public class BootstrapRefusedException extends RuntimeException {
    public BootstrapRefusedException(String message) {
        super(message);
    }
}
