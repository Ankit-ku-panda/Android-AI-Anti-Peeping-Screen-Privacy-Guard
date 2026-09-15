package com.ankitkupanda.privacyguard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OwnerAuthorizationGateTest {
    @Test
    public void briefSingleFaceScoreDropKeepsOwnerAuthorized() {
        OwnerAuthorizationGate gate = new OwnerAuthorizationGate(900L);
        assertTrue(gate.update(1_000L, 1, true));
        assertTrue(gate.update(1_900L, 1, false));
        assertFalse(gate.update(1_901L, 1, false));
    }

    @Test
    public void multipleFacesCancelGraceImmediately() {
        OwnerAuthorizationGate gate = new OwnerAuthorizationGate(900L);
        assertTrue(gate.update(1_000L, 1, true));
        assertFalse(gate.update(1_100L, 2, true));
        assertFalse(gate.update(1_200L, 1, false));
    }

    @Test
    public void noPriorMatchCannotUseGrace() {
        OwnerAuthorizationGate gate = new OwnerAuthorizationGate(900L);
        assertFalse(gate.update(1_000L, 1, false));
    }
}
