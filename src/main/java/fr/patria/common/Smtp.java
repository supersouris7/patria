package fr.patria.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Envoi d'un e-mail texte par SMTP, sans bibliotheque : TLS direct (port 465) ou STARTTLS (port 587), authentification
 * PLAIN. Les adresses et l'objet sont verifies (aucun retour a la ligne) : impossible d'injecter un en-tete. Le corps
 * part en base64, en UTF-8.
 */
public final class Smtp {
    /** Chiffrement de la connexion au serveur d'envoi. */
    public enum Security { STARTTLS, TLS, NONE }

    /** Reglages du serveur d'envoi ; {@code host} vide : aucun envoi. */
    public record Settings(String host, int port, Security security, String user, String password, String from) {
        public static final Settings NONE = new Settings("", 587, Security.STARTTLS, "", "", "");

        public boolean configured() {
            return !host.isBlank() && !from.isBlank();
        }
    }

    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}");
    private static final int TIMEOUT_MILLIS = 15_000;

    private Smtp() {
    }

    /** Adresse e-mail plausible, sans espace ni retour a la ligne. */
    public static boolean valid(String address) {
        return address != null && address.length() <= 254 && ADDRESS.matcher(address).matches();
    }

    public static void send(Settings settings, String to, String subject, String body) throws IOException {
        send(settings, to, subject, body, null);
    }

    /** {@code html} non nul : e-mail en deux versions (texte et HTML), la messagerie choisit. */
    public static void send(Settings settings, String to, String subject, String body, String html) throws IOException {
        if (!settings.configured()) throw new IOException("Serveur d'envoi non réglé.");
        if (!valid(to) || !valid(settings.from())) throw new IOException("Adresse e-mail invalide.");
        if (subject.contains("\r") || subject.contains("\n")) throw new IOException("Objet invalide.");
        Socket socket = settings.security() == Security.TLS ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try (socket) {
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            Session session = new Session(socket);
            session.expect(220);
            session.command("EHLO patria", 250);
            if (settings.security() == Security.STARTTLS) {
                session.command("STARTTLS", 220);
                SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, settings.host(), settings.port(), true);
                secure.startHandshake();
                session = new Session(secure);
                session.command("EHLO patria", 250);
            }
            if (!settings.user().isBlank()) {
                String token = Base64.getEncoder().encodeToString(("\0" + settings.user() + "\0" + settings.password()).getBytes(StandardCharsets.UTF_8));
                session.command("AUTH PLAIN " + token, 235);
            }
            session.command("MAIL FROM:<" + settings.from() + ">", 250);
            session.command("RCPT TO:<" + to + ">", 250, 251);
            session.command("DATA", 354);
            session.write(message(settings.from(), to, subject, body, html));
            session.command(".", 250);
            session.command("QUIT", 221);
        }
    }

    static String message(String from, String to, String subject, String body, String html) {
        String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
        String headers = "From: Patria <" + from + ">\r\nTo: <" + to + ">\r\nSubject: " + encodedSubject + "\r\nDate: "
                + ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME) + "\r\nMessage-ID: <" + java.util.UUID.randomUUID() + "@patria>\r\nMIME-Version: 1.0\r\n";
        String text = encode(body.replace("\r\n", "\n").replace("\n", "\r\n"));
        if (html == null) return headers + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + text + "\r\n";
        String boundary = "patria-" + java.util.UUID.randomUUID();
        return headers + "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"\r\n\r\n"
                + "--" + boundary + "\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + text + "\r\n"
                + "--" + boundary + "\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + encode(html) + "\r\n"
                + "--" + boundary + "--\r\n";
    }

    private static String encode(String text) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Dialogue ligne a ligne avec le serveur : une commande, puis le code de reponse attendu. */
    private static final class Session {
        private final BufferedReader in;
        private final OutputStream out;

        Session(Socket socket) throws IOException {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            out = socket.getOutputStream();
        }

        void command(String line, int... expected) throws IOException {
            write(line + "\r\n");
            expect(expected);
        }

        void write(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void expect(int... expected) throws IOException {
            String line;
            do {
                line = in.readLine();
                if (line == null || line.length() < 3) throw new IOException("Réponse SMTP inattendue.");
            } while (line.length() > 3 && line.charAt(3) == '-');
            int code = Integer.parseInt(line.substring(0, 3));
            for (int value : expected) if (value == code) return;
            // Le texte du serveur peut contenir l'adresse : on ne garde que le code.
            throw new IOException("Le serveur d'envoi a refusé (code " + code + ").");
        }
    }

    public static Security security(String value) {
        try {
            return Security.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Security.STARTTLS;
        }
    }
}
