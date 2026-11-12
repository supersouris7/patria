// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PwnedPasswordsTest {
    @Test
    void sendsOnlyTheHashPrefixAndReadsTheMatchingSuffix() {
        // SHA-1 de « password » : 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
        String[] asked = new String[1];
        PwnedPasswords check = new PwnedPasswords(prefix -> {
            asked[0] = prefix;
            return "0018A45C4D1DEF81644B54AB7F969B88D65:0\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:3861493\r\n";
        });
        assertTrue(check.breached("password"));
        assertEquals("5BAA6", asked[0], "seuls 5 caractères de l'empreinte partent");
        assertFalse(new PwnedPasswords(prefix -> "1E4C9B93F3F0682250B6CF8331B7EE68FD8:0\r\n").breached("password"), "bourrage à 0 : absent");
        assertFalse(new PwnedPasswords(prefix -> { throw new java.io.IOException("panne"); }).breached("password"), "panne : on ne bloque personne");
    }
}
