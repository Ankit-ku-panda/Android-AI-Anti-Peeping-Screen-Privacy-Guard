package com.ankitkupanda.privacyguard;

/**
 * Converts flickering frame-by-frame face counts into a stable privacy decision.
 * This mirrors the time-smoothed state machine in the desktop Python project.
 */
public final class RiskEngine {
    public static final String EXTRA_VIEWER_REASON = "Another viewer may be looking at the screen";
    public static final String UNKNOWN_FACE_REASON = "An unrecognized face is viewing the screen";
    public static final String NO_USER_REASON = "No primary user is visible";

    public static final class Decision {
        public final boolean protectedScreen;
        public final String reason;
        public final float progress;
        public final boolean paused;

        Decision(boolean protectedScreen, String reason, float progress, boolean paused) {
            this.protectedScreen = protectedScreen;
            this.reason = reason;
            this.progress = progress;
            this.paused = paused;
        }
    }

    private final long peepingTriggerMs;
    private final long noFaceTriggerMs;
    private final long safeReleaseMs;

    private boolean protectedScreen;
    private long riskSince = -1L;
    private String riskReason;
    private long safeSince = -1L;
    private long pausedUntil;

    public RiskEngine(long peepingTriggerMs, long noFaceTriggerMs, long safeReleaseMs) {
        if (peepingTriggerMs <= 0 || noFaceTriggerMs <= 0 || safeReleaseMs <= 0) {
            throw new IllegalArgumentException("All timing values must be positive");
        }
        this.peepingTriggerMs = peepingTriggerMs;
        this.noFaceTriggerMs = noFaceTriggerMs;
        this.safeReleaseMs = safeReleaseMs;
    }

    public synchronized void reset() {
        protectedScreen = false;
        riskSince = -1L;
        riskReason = null;
        safeSince = -1L;
        pausedUntil = 0L;
    }

    public synchronized void pause(long nowMs, long durationMs) {
        protectedScreen = false;
        pausedUntil = nowMs + Math.max(0L, durationMs);
        riskSince = -1L;
        riskReason = null;
        safeSince = -1L;
    }

    public synchronized Decision update(long nowMs, int faceCount, boolean protectWhenNoFace) {
        return updateAuthorization(nowMs, faceCount, faceCount == 1, protectWhenNoFace);
    }

    /** Updates risk using both face count and whether the sole visible face matches the owner. */
    public synchronized Decision updateAuthorization(long nowMs, int faceCount,
                                                      boolean ownerAuthorized,
                                                      boolean protectWhenNoFace) {
        if (faceCount < 0) {
            throw new IllegalArgumentException("faceCount cannot be negative");
        }

        if (nowMs < pausedUntil) {
            float remaining = (pausedUntil - nowMs) / 1_000f;
            return new Decision(false,
                    String.format(java.util.Locale.US, "Protection paused (%.1fs remaining)", remaining),
                    0f,
                    true);
        }

        String activeReason = null;
        long triggerMs = peepingTriggerMs;
        if (faceCount > 1) {
            activeReason = EXTRA_VIEWER_REASON;
        } else if (faceCount == 1 && !ownerAuthorized) {
            activeReason = UNKNOWN_FACE_REASON;
        } else if (protectWhenNoFace && faceCount == 0) {
            activeReason = NO_USER_REASON;
            triggerMs = noFaceTriggerMs;
        }

        if (activeReason != null) {
            safeSince = -1L;
            if (!activeReason.equals(riskReason) || riskSince < 0L) {
                riskReason = activeReason;
                riskSince = nowMs;
            }

            long elapsed = Math.max(0L, nowMs - riskSince);
            float progress = Math.min(1f, elapsed / (float) triggerMs);
            if (protectedScreen || elapsed >= triggerMs) {
                protectedScreen = true;
                return new Decision(true, activeReason, 1f, false);
            }
            return new Decision(false, "Checking: " + activeReason.toLowerCase(java.util.Locale.US),
                    progress, false);
        }

        riskSince = -1L;
        riskReason = null;

        if (protectedScreen) {
            if (safeSince < 0L) {
                safeSince = nowMs;
            }
            long safeElapsed = Math.max(0L, nowMs - safeSince);
            if (safeElapsed >= safeReleaseMs) {
                protectedScreen = false;
                safeSince = -1L;
                return new Decision(false, "Screen clear", 0f, false);
            }
            float releaseProgress = Math.min(1f, safeElapsed / (float) safeReleaseMs);
            return new Decision(true, "Waiting for a stable safe view", 1f - releaseProgress, false);
        }

        safeSince = -1L;
        return new Decision(false, "Screen clear", 0f, false);
    }
}
