package fr.patria.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.mindrot.jbcrypt.BCrypt;

/** Mot de passe de l'application (APP_PASSWORD), en clair ou au format {@code {bcrypt}...}. */
final class Password {
    private static final String BCRYPT = "{bcrypt}";
    private final String configured;

    Password(String configured) {
        this.configured = configured;
    }

    boolean matches(String entered) {
        if (configured.startsWith(BCRYPT)) return BCrypt.checkpw(entered, configured.substring(BCRYPT.length()));
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), entered.getBytes(StandardCharsets.UTF_8));
    }
}
