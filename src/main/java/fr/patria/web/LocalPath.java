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

    public static String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }
}
