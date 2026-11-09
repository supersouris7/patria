package fr.patria.account;

import fr.patria.common.Smtp;
import fr.patria.common.ValidationException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * E-mails de Patria (confirmation d'adresse, mot de passe oublie, test), selon le reglage de l'administrateur. Les
 * liens partent de l'adresse publique reglee, jamais de l'en-tete Host de la requete (qu'un tiers pourrait forger).
 */
final class Mails {
    private static final Logger LOG = Logger.getLogger(Mails.class.getName());

    private Mails() {
    }

    /** Lien absolu vers {@code path} (commencant par /) sur l'adresse publique de l'installation. */
    static String link(AccountRepository.Mail mail, String path) {
        String base = mail.publicUrl().endsWith("/") ? mail.publicUrl().substring(0, mail.publicUrl().length() - 1) : mail.publicUrl();
        return base + path;
    }

    /** Envoie ou explique l'echec (sans detail technique pour la personne ; le detail va au journal). */
    static void send(AccountRepository.Mail mail, String to, String subject, String body) {
        if (!mail.active()) throw new ValidationException("L'envoi d'e-mails n'est pas réglé sur cette installation.");
        try {
            Smtp.send(mail.smtp(), to, subject, body + "\n\n-- \nPatria, votre budget sur votre serveur. Si vous n'êtes pas à l'origine de ce message, ignorez-le.");
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Envoi d'e-mail impossible", exception);
            throw new ValidationException("L'e-mail n'a pas pu partir (" + exception.getMessage() + "). Réessayez plus tard ou prévenez l'administrateur.");
        }
    }

    /** Adresse saisie, nettoyee ; vide si rien n'est saisi ; erreur si elle n'est pas valable. */
    static String address(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) return "";
        if (!Smtp.valid(value)) throw new ValidationException("Adresse e-mail invalide.");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
