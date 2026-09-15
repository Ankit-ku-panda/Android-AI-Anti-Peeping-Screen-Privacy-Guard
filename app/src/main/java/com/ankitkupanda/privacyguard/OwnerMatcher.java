package com.ankitkupanda.privacyguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compares live face signatures with the owner's enrolled samples. */
public final class OwnerMatcher {
    private static final float MINIMUM_CALIBRATED_THRESHOLD = 0.60f;
    private static final float CALIBRATION_MARGIN = 0.05f;

    public static final class Match {
        public final boolean authorized;
        public final float similarity;
        public final float requiredSimilarity;

        Match(boolean authorized, float similarity, float requiredSimilarity) {
            this.authorized = authorized;
            this.similarity = similarity;
            this.requiredSimilarity = requiredSimilarity;
        }
    }

    private final float maximumThreshold;
    private float effectiveThreshold;
    private List<float[]> templates = Collections.emptyList();

    public OwnerMatcher(float maximumThreshold) {
        if (maximumThreshold < MINIMUM_CALIBRATED_THRESHOLD || maximumThreshold > 1f) {
            throw new IllegalArgumentException("threshold must be between 0.60 and 1.0");
        }
        this.maximumThreshold = maximumThreshold;
        effectiveThreshold = maximumThreshold;
    }

    public synchronized void setTemplates(List<float[]> values) {
        List<float[]> copies = new ArrayList<>();
        if (values != null) {
            for (float[] value : values) {
                if (value != null && value.length == FaceSignature.DIMENSION) {
                    copies.add(value.clone());
                }
            }
        }
        templates = Collections.unmodifiableList(copies);
        effectiveThreshold = calibrateThreshold(copies);
    }

    public synchronized boolean hasProfile() {
        return !templates.isEmpty();
    }

    public synchronized float threshold() {
        return effectiveThreshold;
    }

    public synchronized Match match(List<float[]> candidates) {
        float best = -1f;
        if (candidates != null) {
            for (float[] candidate : candidates) {
                for (float[] template : templates) {
                    best = Math.max(best, FaceSignature.cosine(candidate, template));
                }
            }
        }
        return new Match(best >= effectiveThreshold, best, effectiveThreshold);
    }

    /**
     * Uses the lower fifth of enrollment-to-enrollment similarities so normal
     * pose variation can lower an overly strict default, but never below 0.60.
     */
    private float calibrateThreshold(List<float[]> values) {
        if (values.size() < 3) {
            return maximumThreshold;
        }
        List<Float> similarities = new ArrayList<>();
        for (int first = 0; first < values.size(); first++) {
            for (int second = first + 1; second < values.size(); second++) {
                float similarity = FaceSignature.cosine(values.get(first), values.get(second));
                if (similarity >= 0f && Float.isFinite(similarity)) {
                    similarities.add(similarity);
                }
            }
        }
        if (similarities.isEmpty()) {
            return maximumThreshold;
        }
        Collections.sort(similarities);
        int percentileIndex = Math.min(similarities.size() - 1,
                Math.round((similarities.size() - 1) * 0.20f));
        float calibrated = similarities.get(percentileIndex) - CALIBRATION_MARGIN;
        return Math.max(MINIMUM_CALIBRATED_THRESHOLD,
                Math.min(maximumThreshold, calibrated));
    }
}
