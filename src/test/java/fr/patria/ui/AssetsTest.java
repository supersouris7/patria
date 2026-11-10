// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AssetsTest {
    @Test
    void theStylesheetJoinsItsPartsInOrder() {
        String css = new String(Assets.FILES.get("/css/patria.css").content(), StandardCharsets.UTF_8);
        int base = css.indexOf("/* 01-base.css */");
        int responsive = css.indexOf("/* 03-responsive.css */");
        int security = css.indexOf("/* 07-securite-comptes.css */");
        assertTrue(base >= 0 && base < responsive && responsive < security, "ordre de parts/files.txt");
        assertTrue(css.contains("--bg:") && css.contains(".audience-panel") && css.contains("@media"), "toutes les parties");
    }
}
