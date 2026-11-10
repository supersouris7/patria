// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {
    /** Secret de reference de la RFC 6238 (« 12345678901234567890 »). */
    private static final String SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheReferenceValues() {
        // RFC 6238, annexe B (8 chiffres) : 94287082, 07081804, 14050471 ; les 6 derniers chiffres
        assertEquals("287082", Totp.code(SECRET, Totp.step(59)));
        assertEquals("081804", Totp.code(SECRET, Totp.step(1111111109)));
        assertEquals("050471", Totp.code(SECRET, Totp.step(1111111111)));
    }

    @Test
    void acceptsANeighbourStepOnlyOnce() {
        long now = 1_700_000_000;
        String previous = Totp.code(SECRET, Totp.step(now) - 1);
        long used = Totp.verify(SECRET, previous, now, -1);
        assertEquals(Totp.step(now) - 1, used, "horloge un peu en retard");
        assertEquals(-1, Totp.verify(SECRET, previous, now, used), "un code ne sert qu'une fois");
        assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, Totp.step(now) - 3), now, -1), "trop ancien");
        assertEquals(-1, Totp.verify(SECRET, "12a456", now, -1));
        assertTrue(Totp.verify(SECRET, Totp.code(SECRET, Totp.step(now)).substring(0, 3) + " " + Totp.code(SECRET, Totp.step(now)).substring(3), now, -1) > 0,
                "espaces acceptés");
        assertEquals(SECRET.length(), Totp.newSecret().length());
        assertTrue(Totp.uri("Patria", "paul", SECRET).startsWith("otpauth://totp/Patria%3Apaul?secret="));
    }
}
