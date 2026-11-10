// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fichiers statiques (CSS, JS, logo) charges une fois au demarrage et versionnes par empreinte. */
public final class Assets {
    public record Asset(String contentType, byte[] content) {
    }

    /** Chemin public -> fichier. Le parametre {@code ?v=} change a chaque modification : cache navigateur illimite. */
    public static final Map<String, Asset> FILES = load(Map.of(
            "/js/app.js", "text/javascript; charset=utf-8",
            "/images/patria-logo.png", "image/png"));
    public static final String VERSION = version(FILES);

    private Assets() {
    }

    public static String url(String path) {
        return path + "?v=" + VERSION;
    }

    private static Map<String, Asset> load(Map<String, String> types) {
        Map<String, Asset> files = new LinkedHashMap<>();
        Map<String, String> all = new LinkedHashMap<>(types);
        files.put("/css/patria.css", new Asset("text/css; charset=utf-8", stylesheet()));
        all.forEach((path, type) -> {
            try (InputStream input = Assets.class.getClassLoader().getResourceAsStream("static" + path)) {
                if (input == null) throw new IllegalStateException("Ressource manquante : static" + path);
                files.put(path, new Asset(type, input.readAllBytes()));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        return Map.copyOf(files);
    }

    /**
     * Feuille de style unique, servie a {@code /css/patria.css} : les fichiers de {@code static/css/parts}, mis bout a
     * bout dans l'ordre de {@code parts/files.txt} (la cascade CSS en depend). Une seule requete pour le navigateur.
     */
    static byte[] stylesheet() {
        try (InputStream manifest = Assets.class.getResourceAsStream("/static/css/parts/files.txt")) {
            if (manifest == null) throw new IllegalStateException("Liste des feuilles de style manquante : static/css/parts/files.txt");
            StringBuilder css = new StringBuilder();
            for (String name : new String(manifest.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().map(String::trim).filter(line -> !line.isEmpty()).toList()) {
                try (InputStream part = Assets.class.getResourceAsStream("/static/css/parts/" + name)) {
                    if (part == null) throw new IllegalStateException("Feuille de style manquante : static/css/parts/" + name);
                    css.append("/* ").append(name).append(" */\n").append(new String(part.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).append('\n');
                }
            }
            return css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String version(Map<String, Asset> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.keySet().stream().sorted().forEach(path -> digest.update(files.get(path).content()));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 10);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
