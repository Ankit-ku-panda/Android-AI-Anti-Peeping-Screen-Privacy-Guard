package com.ankitkupanda.privacyguard;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class OwnerMatcherTest {
    @Test
    public void exactEnrolledTemplateIsAuthorized() {
        float[] enrolled = templateAt(10);
        OwnerMatcher matcher = new OwnerMatcher(0.82f);
        matcher.setTemplates(Collections.singletonList(enrolled));

        assertTrue(matcher.hasProfile());
        assertTrue(matcher.match(Collections.singletonList(enrolled.clone())).authorized);
    }

    @Test
    public void unrelatedTemplateIsRejected() {
        OwnerMatcher matcher = new OwnerMatcher(0.82f);
        matcher.setTemplates(Collections.singletonList(templateAt(10)));

        assertFalse(matcher.match(Arrays.asList(templateAt(20), templateAt(30))).authorized);
        assertFalse(matcher.match(Collections.emptyList()).authorized);
    }

    @Test
    public void diverseEnrollmentCalibratesButNeverBelowSafetyFloor() {
        OwnerMatcher matcher = new OwnerMatcher(0.72f);
        matcher.setTemplates(Arrays.asList(templateAt(10), templateAt(20), templateAt(30)));
        assertEquals(0.60f, matcher.threshold(), 0.0001f);

        float[] nearOwner = new float[FaceSignature.DIMENSION];
        nearOwner[10] = 0.65f;
        nearOwner[40] = (float) Math.sqrt(1f - 0.65f * 0.65f);
        assertTrue(matcher.match(Collections.singletonList(nearOwner)).authorized);
    }

    private float[] templateAt(int index) {
        float[] template = new float[FaceSignature.DIMENSION];
        template[index] = 1f;
        return template;
    }
}
