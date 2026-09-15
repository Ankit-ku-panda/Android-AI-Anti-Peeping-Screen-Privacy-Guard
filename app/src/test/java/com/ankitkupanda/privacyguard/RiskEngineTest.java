package com.ankitkupanda.privacyguard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RiskEngineTest {
    private RiskEngine engine() {
        return new RiskEngine(800L, 1_500L, 1_500L);
    }

    @Test
    public void oneFaceStaysClear() {
        RiskEngine.Decision decision = engine().update(1_000L, 1, true);
        assertFalse(decision.protectedScreen);
        assertFalse(decision.paused);
    }

    @Test
    public void extraFaceMustPersistBeforeShield() {
        RiskEngine engine = engine();
        assertFalse(engine.update(1_000L, 2, true).protectedScreen);
        assertFalse(engine.update(1_799L, 2, true).protectedScreen);
        assertTrue(engine.update(1_800L, 2, true).protectedScreen);
    }

    @Test
    public void noFaceUsesLongerDelayInStrictMode() {
        RiskEngine engine = engine();
        assertFalse(engine.update(5_000L, 0, true).protectedScreen);
        assertFalse(engine.update(6_499L, 0, true).protectedScreen);
        assertTrue(engine.update(6_500L, 0, true).protectedScreen);
    }

    @Test
    public void noFaceIsSafeWhenStrictModeIsOff() {
        RiskEngine engine = engine();
        assertFalse(engine.update(5_000L, 0, false).protectedScreen);
        assertFalse(engine.update(9_000L, 0, false).protectedScreen);
    }

    @Test
    public void shieldRequiresStableSafeViewToRelease() {
        RiskEngine engine = engine();
        engine.update(1_000L, 2, true);
        assertTrue(engine.update(1_800L, 2, true).protectedScreen);
        assertTrue(engine.update(2_000L, 1, true).protectedScreen);
        assertTrue(engine.update(3_499L, 1, true).protectedScreen);
        assertFalse(engine.update(3_500L, 1, true).protectedScreen);
    }

    @Test
    public void emergencyPauseImmediatelyHidesShield() {
        RiskEngine engine = engine();
        engine.update(1_000L, 2, true);
        assertTrue(engine.update(1_800L, 2, true).protectedScreen);
        engine.pause(2_000L, 10_000L);
        RiskEngine.Decision paused = engine.update(2_100L, 2, true);
        assertFalse(paused.protectedScreen);
        assertTrue(paused.paused);
        assertFalse(engine.update(12_000L, 2, true).protectedScreen);
    }
}
