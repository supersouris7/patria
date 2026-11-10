// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
            Smtp.send(mail.smtp(), to, subject, body + "\n\n-- \n" + FOOTER, html(subject, body));
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Envoi d'e-mail impossible", exception);
            throw new ValidationException("L'e-mail n'a pas pu partir (" + exception.getMessage() + "). Réessayez plus tard ou prévenez l'administrateur.");
        }
    }

    private static final String FOOTER = "Patria, votre budget sur votre serveur. Si vous n'êtes pas à l'origine de ce message, ignorez-le.";

    /**
     * Version HTML sobre d'un e-mail : en-tete Patria, paragraphes, et le lien en bouton (styles en ligne, seuls lus
     * par les messageries). Le texte vient de Patria, mais il est echappe quand meme.
     */
    static String html(String subject, String body) {
        StringBuilder content = new StringBuilder();
        for (String paragraph : body.replace("\r\n", "\n").split("\n\\s*\n")) {
            StringBuilder text = new StringBuilder();
            String link = null;
            for (String line : paragraph.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) link = trimmed;
                else if (trimmed.endsWith(":") && trimmed.length() > 1 && paragraph.contains("http")) text.append(escape(trimmed.substring(0, trimmed.length() - 1).strip())).append(".<br>");
                else if (!trimmed.isEmpty()) text.append(escape(trimmed)).append("<br>");
            }
            if (!text.isEmpty()) content.append("<p style=\"margin:0 0 16px;line-height:1.5\">").append(text.substring(0, text.length() - 4)).append("</p>");
            if (link != null) {
                content.append("<p style=\"margin:8px 0 24px\"><a href=\"").append(escape(link)).append("\" style=\"background:#1a78dc;border-radius:8px;color:#ffffff;")
                        .append("display:inline-block;font-weight:700;padding:12px 20px;text-decoration:none\">Ouvrir le lien</a></p>")
                        .append("<p style=\"color:#637284;font-size:12px;margin:0 0 16px;word-break:break-all\">").append(escape(link)).append("</p>");
            }
        }
        return "<!doctype html><html lang=\"fr\"><body style=\"background:#f5f7fa;color:#14202c;font-family:Arial,Helvetica,sans-serif;margin:0;padding:24px\">"
                + "<table role=\"presentation\" width=\"100%\" style=\"max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #dde3ea;border-radius:12px\">"
                + "<tr><td style=\"padding:28px\"><p style=\"color:#1a78dc;font-size:13px;font-weight:700;letter-spacing:.08em;margin:0 0 8px;text-transform:uppercase\">Patria</p>"
                + "<h1 style=\"font-size:20px;margin:0 0 20px\">" + escape(subject.replaceFirst("^Patria : ", "")) + "</h1>" + content
                + "<p style=\"border-top:1px solid #dde3ea;color:#637284;font-size:12px;margin:24px 0 0;padding-top:16px\">" + escape(FOOTER) + "</p></td></tr></table></body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Adresse saisie, nettoyee ; vide si rien n'est saisi ; erreur si elle n'est pas valable. */
    static String address(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) return "";
        if (!Smtp.valid(value)) throw new ValidationException("Adresse e-mail invalide.");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
