// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mot de passe present dans des fuites connues (service Have I Been Pwned, par k-anonymat) : seuls les 5 premiers
 * caracteres de l'empreinte SHA-1 partent, jamais le mot de passe ni son empreinte complete. Une panne du service ne
 * bloque personne : le mot de passe est alors accepte.
 */
public final class PwnedPasswords {
    /** Telechargement d'une plage d'empreintes ; remplace en test. */
    @FunctionalInterface
    public interface Source {
        String range(String prefix) throws Exception;
    }

    private static final Logger LOG = Logger.getLogger(PwnedPasswords.class.getName());
    private final Source source;

    public PwnedPasswords(Source source) {
        this.source = source;
    }

    /** Service public, en-tete de bourrage (les reponses ont toutes la meme taille), 3 secondes au plus. */
    public static PwnedPasswords online() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return new PwnedPasswords(prefix -> client.send(HttpRequest.newBuilder(URI.create("https://api.pwnedpasswords.com/range/" + prefix))
                .header("Add-Padding", "true").header("User-Agent", "Patria").timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    public boolean breached(String password) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
            String suffix = hash.substring(5);
            for (String line : source.range(hash.substring(0, 5)).split("\\R")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).strip().equals(suffix) && !line.substring(colon + 1).strip().equals("0")) return true;
            }
            return false;
        } catch (Exception exception) {
            LOG.log(Level.FINE, "Vérification des mots de passe compromis indisponible", exception);
            return false;
        }
    }
}
