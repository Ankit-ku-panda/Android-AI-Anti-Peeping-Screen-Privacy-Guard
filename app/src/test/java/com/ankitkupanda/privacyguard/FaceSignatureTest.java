package com.ankitkupanda.privacyguard;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FaceSignatureTest {
    @Test
    public void extractionHasExpectedDimensionAndUnitLength() {
        byte[] image = patternedImage(96, 96, 0);
        float[] signature = FaceSignature.extract(image, 96, 96, 8, 8, 88, 88);

        assertEquals(FaceSignature.DIMENSION, signature.length);
        double squaredLength = 0d;
        for (float value : signature) {
            squaredLength += value * value;
        }
        assertEquals(1d, squaredLength, 0.0001d);
    }

    @Test
    public void equalizationMakesModerateBrightnessChangeStable() {
        float[] original = FaceSignature.extract(
                patternedImage(96, 96, 0), 96, 96, 8, 8, 88, 88);
        float[] brighter = FaceSignature.extract(
                patternedImage(96, 96, 24), 96, 96, 8, 8, 88, 88);

        assertTrue(FaceSignature.cosine(original, brighter) > 0.97f);
    }

    @Test
    public void eyeAlignmentHandlesTranslationScaleAndRoll() {
        byte[] first = alignedPattern(180, 180, 62f, 70f, 102f, 70f);
        byte[] moved = alignedPattern(180, 180, 43f, 64f, 105f, 73f);

        float[] firstSignature = FaceSignature.extractAligned(
                first, 180, 180, 62f, 70f, 102f, 70f);
        float[] movedSignature = FaceSignature.extractAligned(
                moved, 180, 180, 43f, 64f, 105f, 73f);

        assertTrue(FaceSignature.cosine(firstSignature, movedSignature) > 0.95f);
    }

    private byte[] patternedImage(int width, int height, int brightness) {
        byte[] values = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pattern = 30 + ((x * 3 + y * 5 + (x / 7) * 19) % 170);
                values[y * width + x] = (byte) Math.min(255, pattern + brightness);
            }
        }
        return values;
    }

    private byte[] alignedPattern(int width, int height,
                                  float leftEyeX, float leftEyeY,
                                  float rightEyeX, float rightEyeY) {
        byte[] values = new byte[width * height];
        float deltaX = rightEyeX - leftEyeX;
        float deltaY = rightEyeY - leftEyeY;
        float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        float directionX = deltaX / distance;
        float directionY = deltaY / distance;
        float scale = distance / 26f;
        float centerX = (leftEyeX + rightEyeX) * 0.5f;
        float centerY = (leftEyeY + rightEyeY) * 0.5f;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float differenceX = x - centerX;
                float differenceY = y - centerY;
                float canonicalX = 31.5f
                        + (differenceX * directionX + differenceY * directionY) / scale;
                float canonicalY = 22f
                        + (-differenceX * directionY + differenceY * directionX) / scale;
                float value = 18f;
                if (canonicalX >= 0f && canonicalX <= 63f
                        && canonicalY >= 0f && canonicalY <= 63f) {
                    value = 105f + 42f * (float) Math.sin(canonicalX * 0.18f)
                            + 33f * (float) Math.cos(canonicalY * 0.14f)
                            + 24f * (float) Math.sin((canonicalX + canonicalY) * 0.11f);
                }
                values[y * width + x] = (byte) Math.max(0, Math.min(255,
                        Math.round(value)));
            }
        }
        return values;
    }
}
