// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Adresse de retour interne a Patria (apres connexion, apres enregistrement) : jamais vers un autre site. */
public final class LocalPath {
    private LocalPath() {
    }

    /** {@code raw} s'il designe une page de Patria, sinon {@code null}. */
    public static String safe(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 300) return null;
        if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return null;
        for (char c : raw.toCharArray()) if (c < 0x20 || c == '\\' || c == 0x7f) return null;
        return raw;
    }

    private static final java.util.regex.Pattern ASSISTANT = java.util.regex.Pattern.compile("/assistant/[a-z-]+(\\?doc=[a-z0-9-]+)?");

    /** Retour vers une page du guide apres un import ({@code /assistant/depenses}, {@code /assistant/documents?doc=x}), sinon {@code null}. */
    public static String assistant(String raw) {
        return raw != null && ASSISTANT.matcher(raw).matches() ? raw : null;
    }

    /** {@code path} avec {@code ok=} en plus de ses parametres eventuels. */
    public static String withOk(String path, String ok) {
        return path + (path.contains("?") ? "&" : "?") + "ok=" + ok;
    }

    public static String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }
}
