package com.ankitkupanda.privacyguard;

/** Keeps a confirmed owner authorized through very short camera-score drops. */
public final class OwnerAuthorizationGate {
    private final long graceMs;
    private long lastDirectAuthorizationAt = -1L;

    public OwnerAuthorizationGate(long graceMs) {
        if (graceMs < 0L) {
            throw new IllegalArgumentException("graceMs cannot be negative");
        }
        this.graceMs = graceMs;
    }

    public synchronized boolean update(long nowMs, int faceCount,
                                       boolean directlyAuthorized) {
        if (faceCount < 0) {
            throw new IllegalArgumentException("faceCount cannot be negative");
        }
        if (faceCount > 1) {
            lastDirectAuthorizationAt = -1L;
            return false;
        }
        if (faceCount != 1) {
            return false;
        }
        if (directlyAuthorized) {
            lastDirectAuthorizationAt = nowMs;
            return true;
        }
        return lastDirectAuthorizationAt >= 0L
                && nowMs - lastDirectAuthorizationAt <= graceMs;
    }

    public synchronized void reset() {
        lastDirectAuthorizationAt = -1L;
    }
}
