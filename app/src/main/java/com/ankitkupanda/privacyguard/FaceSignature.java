package com.ankitkupanda.privacyguard;

/**
 * Creates an illumination-normalized face signature without storing a photo.
 * Eye alignment makes the signature stable when the detected box moves or rolls.
 */
public final class FaceSignature {
    private static final int NORMALIZED_SIZE = 64;
    private static final int GRID = 8;
    private static final int LBP_BINS = 10;
    private static final int HOG_BINS = 9;
    private static final int APPEARANCE_SIZE = 16;
    private static final float TARGET_EYE_Y = 22f;
    private static final float TARGET_EYE_CENTER_X = 31.5f;
    private static final float TARGET_EYE_DISTANCE = 26f;

    public static final int DIMENSION = GRID * GRID * (LBP_BINS + HOG_BINS)
            + APPEARANCE_SIZE * APPEARANCE_SIZE;

    private FaceSignature() {
    }

    /** Fallback extraction for devices/frames where eye landmarks are unavailable. */
    public static float[] extract(byte[] gray, int frameWidth, int frameHeight,
                                  int left, int top, int right, int bottom) {
        validateFrame(gray, frameWidth, frameHeight);
        left = clamp(left, 0, frameWidth - 1);
        top = clamp(top, 0, frameHeight - 1);
        right = clamp(right, left + 1, frameWidth);
        bottom = clamp(bottom, top + 1, frameHeight);
        return describe(equalize(resampleCrop(
                gray, frameWidth, left, top, right, bottom)));
    }

    /**
     * Maps the two detected eyes to fixed positions before feature extraction.
     * Eye order does not matter; the method sorts them by image x-coordinate.
     */
    public static float[] extractAligned(byte[] gray, int frameWidth, int frameHeight,
                                         float firstEyeX, float firstEyeY,
                                         float secondEyeX, float secondEyeY) {
        validateFrame(gray, frameWidth, frameHeight);
        float leftEyeX = firstEyeX;
        float leftEyeY = firstEyeY;
        float rightEyeX = secondEyeX;
        float rightEyeY = secondEyeY;
        if (leftEyeX > rightEyeX) {
            leftEyeX = secondEyeX;
            leftEyeY = secondEyeY;
            rightEyeX = firstEyeX;
            rightEyeY = firstEyeY;
        }

        float deltaX = rightEyeX - leftEyeX;
        float deltaY = rightEyeY - leftEyeY;
        float eyeDistance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (eyeDistance < 12f || !Float.isFinite(eyeDistance)) {
            throw new IllegalArgumentException("Eye landmarks are too close");
        }

        float directionX = deltaX / eyeDistance;
        float directionY = deltaY / eyeDistance;
        float scale = eyeDistance / TARGET_EYE_DISTANCE;
        float eyeCenterX = (leftEyeX + rightEyeX) * 0.5f;
        float eyeCenterY = (leftEyeY + rightEyeY) * 0.5f;
        float[] values = new float[NORMALIZED_SIZE * NORMALIZED_SIZE];

        for (int y = 0; y < NORMALIZED_SIZE; y++) {
            float relativeY = (y - TARGET_EYE_Y) * scale;
            for (int x = 0; x < NORMALIZED_SIZE; x++) {
                float relativeX = (x - TARGET_EYE_CENTER_X) * scale;
                float sourceX = eyeCenterX + relativeX * directionX
                        - relativeY * directionY;
                float sourceY = eyeCenterY + relativeX * directionY
                        + relativeY * directionX;
                values[y * NORMALIZED_SIZE + x] = sampleBilinear(
                        gray, frameWidth, frameHeight, sourceX, sourceY);
            }
        }
        return describe(equalize(values));
    }

    public static float cosine(float[] first, float[] second) {
        if (first == null || second == null || first.length != second.length) {
            return -1f;
        }
        double dot = 0d;
        double firstNorm = 0d;
        double secondNorm = 0d;
        for (int i = 0; i < first.length; i++) {
            dot += first[i] * second[i];
            firstNorm += first[i] * first[i];
            secondNorm += second[i] * second[i];
        }
        if (firstNorm == 0d || secondNorm == 0d) {
            return -1f;
        }
        return (float) (dot / Math.sqrt(firstNorm * secondNorm));
    }

    private static float[] describe(float[] normalized) {
        float[] signature = new float[DIMENSION];
        int offset = addLbp(normalized, signature, 0);
        offset = addHog(normalized, signature, offset);
        addAppearance(normalized, signature, offset);
        normalize(signature, 0, signature.length);
        return signature;
    }

    private static float[] resampleCrop(byte[] source, int sourceWidth,
                                        int left, int top, int right, int bottom) {
        int cropWidth = right - left;
        int cropHeight = bottom - top;
        float[] values = new float[NORMALIZED_SIZE * NORMALIZED_SIZE];
        for (int y = 0; y < NORMALIZED_SIZE; y++) {
            float sourceY = top + (y + 0.5f) * cropHeight / NORMALIZED_SIZE - 0.5f;
            for (int x = 0; x < NORMALIZED_SIZE; x++) {
                float sourceX = left + (x + 0.5f) * cropWidth / NORMALIZED_SIZE - 0.5f;
                values[y * NORMALIZED_SIZE + x] = sampleBilinear(
                        source, sourceWidth, source.length / sourceWidth, sourceX, sourceY);
            }
        }
        return values;
    }

    private static float sampleBilinear(byte[] source, int width, int height,
                                        float x, float y) {
        x = Math.max(0f, Math.min(width - 1f, x));
        y = Math.max(0f, Math.min(height - 1f, y));
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float xWeight = x - x0;
        float yWeight = y - y0;
        float top = (source[y0 * width + x0] & 0xff) * (1f - xWeight)
                + (source[y0 * width + x1] & 0xff) * xWeight;
        float bottom = (source[y1 * width + x0] & 0xff) * (1f - xWeight)
                + (source[y1 * width + x1] & 0xff) * xWeight;
        return top * (1f - yWeight) + bottom * yWeight;
    }

    private static float[] equalize(float[] values) {
        int[] histogram = new int[256];
        for (float value : values) {
            histogram[clamp(Math.round(value), 0, 255)]++;
        }

        int[] cumulative = new int[256];
        int running = 0;
        int firstNonZero = 0;
        boolean found = false;
        for (int i = 0; i < histogram.length; i++) {
            running += histogram[i];
            cumulative[i] = running;
            if (!found && histogram[i] > 0) {
                firstNonZero = running;
                found = true;
            }
        }

        float[] equalized = new float[values.length];
        int denominator = Math.max(1, values.length - firstNonZero);
        for (int i = 0; i < values.length; i++) {
            int value = clamp(Math.round(values[i]), 0, 255);
            equalized[i] = Math.max(0f,
                    (cumulative[value] - firstNonZero) / (float) denominator);
        }
        return equalized;
    }

    private static int addLbp(float[] image, float[] output, int offset) {
        for (int y = 1; y < NORMALIZED_SIZE - 1; y++) {
            for (int x = 1; x < NORMALIZED_SIZE - 1; x++) {
                float center = image[y * NORMALIZED_SIZE + x];
                int code = 0;
                code |= bit(image[(y - 1) * NORMALIZED_SIZE + (x - 1)], center, 7);
                code |= bit(image[(y - 1) * NORMALIZED_SIZE + x], center, 6);
                code |= bit(image[(y - 1) * NORMALIZED_SIZE + (x + 1)], center, 5);
                code |= bit(image[y * NORMALIZED_SIZE + (x + 1)], center, 4);
                code |= bit(image[(y + 1) * NORMALIZED_SIZE + (x + 1)], center, 3);
                code |= bit(image[(y + 1) * NORMALIZED_SIZE + x], center, 2);
                code |= bit(image[(y + 1) * NORMALIZED_SIZE + (x - 1)], center, 1);
                code |= bit(image[y * NORMALIZED_SIZE + (x - 1)], center, 0);

                int rotated = ((code << 1) | (code >>> 7)) & 0xff;
                int transitions = Integer.bitCount((code ^ rotated) & 0xff);
                int bin = transitions <= 2 ? Integer.bitCount(code) : 9;
                int cellX = Math.min(GRID - 1, x * GRID / NORMALIZED_SIZE);
                int cellY = Math.min(GRID - 1, y * GRID / NORMALIZED_SIZE);
                output[offset + (cellY * GRID + cellX) * LBP_BINS + bin] += 0.70f;
            }
        }
        normalizeCells(output, offset, LBP_BINS);
        return offset + GRID * GRID * LBP_BINS;
    }

    private static int addHog(float[] image, float[] output, int offset) {
        for (int y = 1; y < NORMALIZED_SIZE - 1; y++) {
            for (int x = 1; x < NORMALIZED_SIZE - 1; x++) {
                float dx = image[y * NORMALIZED_SIZE + x + 1]
                        - image[y * NORMALIZED_SIZE + x - 1];
                float dy = image[(y + 1) * NORMALIZED_SIZE + x]
                        - image[(y - 1) * NORMALIZED_SIZE + x];
                double angle = Math.atan2(dy, dx);
                if (angle < 0d) {
                    angle += Math.PI;
                }
                if (angle >= Math.PI) {
                    angle -= Math.PI;
                }
                int bin = Math.min(HOG_BINS - 1,
                        (int) (angle * HOG_BINS / Math.PI));
                float magnitude = (float) Math.sqrt(dx * dx + dy * dy);
                int cellX = Math.min(GRID - 1, x * GRID / NORMALIZED_SIZE);
                int cellY = Math.min(GRID - 1, y * GRID / NORMALIZED_SIZE);
                output[offset + (cellY * GRID + cellX) * HOG_BINS + bin]
                        += magnitude * 0.45f;
            }
        }
        normalizeCells(output, offset, HOG_BINS);
        return offset + GRID * GRID * HOG_BINS;
    }

    private static void addAppearance(float[] image, float[] output, int offset) {
        int block = NORMALIZED_SIZE / APPEARANCE_SIZE;
        float mean = 0f;
        for (int y = 0; y < APPEARANCE_SIZE; y++) {
            for (int x = 0; x < APPEARANCE_SIZE; x++) {
                float sum = 0f;
                for (int by = 0; by < block; by++) {
                    for (int bx = 0; bx < block; bx++) {
                        int px = x * block + bx;
                        int py = y * block + by;
                        sum += image[py * NORMALIZED_SIZE + px];
                    }
                }
                float value = sum / (block * block);
                output[offset + y * APPEARANCE_SIZE + x] = value;
                mean += value;
            }
        }
        mean /= APPEARANCE_SIZE * APPEARANCE_SIZE;
        float variance = 0f;
        for (int i = offset; i < output.length; i++) {
            output[i] -= mean;
            variance += output[i] * output[i];
        }
        float scale = (float) Math.sqrt(
                variance / (APPEARANCE_SIZE * APPEARANCE_SIZE) + 1e-6f);
        for (int i = offset; i < output.length; i++) {
            output[i] = 0.65f * output[i] / scale;
        }
    }

    private static void normalizeCells(float[] values, int offset, int bins) {
        for (int cell = 0; cell < GRID * GRID; cell++) {
            normalize(values, offset + cell * bins, offset + (cell + 1) * bins);
        }
    }

    private static void normalize(float[] values, int start, int end) {
        double norm = 0d;
        for (int i = start; i < end; i++) {
            norm += values[i] * values[i];
        }
        float divisor = (float) Math.sqrt(norm + 1e-8d);
        for (int i = start; i < end; i++) {
            values[i] /= divisor;
        }
    }

    private static void validateFrame(byte[] gray, int width, int height) {
        if (gray == null || width <= 0 || height <= 0 || gray.length != width * height) {
            throw new IllegalArgumentException("Invalid grayscale frame");
        }
    }

    private static int bit(float value, float center, int position) {
        return value >= center ? 1 << position : 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
