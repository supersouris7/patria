// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Version et commit de cette installation (inscrits a la compilation dans {@code patria-build.properties}), et lien
 * « Soutenir » (Ko-fi) choisi au demarrage.
 */
public final class BuildInfo {
    private static final Properties PROPERTIES = load();
    private static volatile String supportUrl;

    private BuildInfo() {
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/patria-build.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException exception) {
            // Version inconnue : le pied de page affiche « dev ».
        }
        return properties;
    }

    public static String version() {
        return value("version", "dev");
    }

    /** Commit complet, ou « dev » pour une compilation locale. */
    public static String commit() {
        return value("commit", "dev");
    }

    /** Sept premiers caracteres du commit, comme sur GitHub. */
    public static String shortCommit() {
        String commit = commit();
        return commit.matches("[0-9a-f]{8,40}") ? commit.substring(0, 7) : commit;
    }

    static final String REPOSITORY = "https://github.com/supersouris7/patria";
    static final String IMAGE = "https://hub.docker.com/r/supersouris7/patria/tags?name=";

    /** Page GitHub du commit, ou {@code null} pour une compilation locale. */
    public static String commitUrl() {
        String commit = commit();
        return commit.matches("[0-9a-f]{7,40}") ? REPOSITORY + "/commit/" + commit : null;
    }

    /**
     * Image Docker Hub de cette version : le tag X.Y.Z pour une version stable, {@code unstable} pour la branche main ;
     * {@code null} pour une compilation locale (rien de publie).
     */
    public static String imageUrl() {
        if (commitUrl() == null) return null;
        return IMAGE + ("stable".equals(value("channel", "")) ? version() : "unstable");
    }

    private static String value(String key, String fallback) {
        String value = PROPERTIES.getProperty(key, "").trim();
        return value.isEmpty() || value.startsWith("${") ? fallback : value;
    }

    /** Page Ko-fi, ou {@code null} si le lien « Soutenir » est masque. */
    public static String supportUrl() {
        return supportUrl;
    }

    public static void useSupportUrl(String url) {
        supportUrl = url;
    }
}
