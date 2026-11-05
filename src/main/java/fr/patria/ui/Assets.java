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
            "/css/patria.css", "text/css; charset=utf-8",
            "/js/app.js", "text/javascript; charset=utf-8",
            "/js/redaction.mjs", "text/javascript; charset=utf-8",
            "/js/redaction-pdf.mjs", "text/javascript; charset=utf-8",
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
        try (InputStream manifest = Assets.class.getResourceAsStream("/static/vendor/pdfjs/files.txt")) {
            if (manifest == null) throw new IllegalStateException("Manifest PDF.js manquant.");
            new String(manifest.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().forEach(name ->
                    all.put("/vendor/pdfjs/" + name, name.endsWith(".mjs") || name.endsWith(".js") ? "text/javascript; charset=utf-8"
                            : name.endsWith(".wasm") ? "application/wasm" : "application/octet-stream"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
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
