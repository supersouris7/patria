// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ai;

import fr.patria.common.ValidationException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IA locale facultative (serveur Ollama de l'installation, reglee par l'administrateur). Elle convertit un document
 * (texte d'un PDF, CSV ou texte) en lignes au format d'un import, a partir du meme prompt que celui propose a copier.
 * Rien ne sort de l'installation. Sans IA locale, les imports proposent de copier le prompt dans un assistant externe.
 */
public final class LocalAi {
    /** Reglages de l'administrateur : active ou non, adresse du serveur Ollama et modele. */
    public record Settings(boolean enabled, String url, String model) {
        public static final String DEFAULT_URL = "http://ollama:11434";
        public static final String DEFAULT_MODEL = "qwen2.5:3b";
        public static final Settings OFF = new Settings(false, DEFAULT_URL, DEFAULT_MODEL);

        public Settings {
            url = url == null || url.isBlank() ? DEFAULT_URL : url.strip().replaceAll("/+$", "");
            model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
            if (!url.matches("https?://[^\\s]+")) throw new ValidationException("Adresse de l'IA locale : http:// ou https://, par exemple " + DEFAULT_URL + ".");
            if (!model.matches("[A-Za-z0-9._:/-]{1,80}")) throw new ValidationException("Nom de modèle invalide, par exemple " + DEFAULT_MODEL + ".");
        }
    }

    /** Texte envoye au plus au modele : au-dela, la fin du document est ignoree. */
    static final int MAX_TEXT = 60_000;
    private static volatile Settings settings = Settings.OFF;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private LocalAi() {
    }

    private static volatile String status;

    /** Resultat du dernier test (page Securite de l'administrateur). */
    public static void remember(String message) {
        status = message;
    }

    public static String status() {
        return status;
    }

    public static void configure(Settings value) {
        settings = value == null ? Settings.OFF : value;
    }

    public static Settings settings() {
        return settings;
    }

    public static boolean enabled() {
        return settings.enabled();
    }

    /**
     * Verifie que le serveur repond et que le modele est installe ; renvoie un message pour l'administrateur.
     */
    public static String check(Settings candidate) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(candidate.url() + "/api/tags")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "Le serveur répond, mais pas comme Ollama (erreur " + response.statusCode() + ").";
            String wanted = candidate.model().contains(":") ? candidate.model() : candidate.model() + ":latest";
            return response.body().contains("\"" + wanted + "\"") || response.body().contains("\"" + candidate.model() + "\"")
                    ? "IA locale prête : le modèle " + candidate.model() + " est installé."
                    : "Le serveur répond, mais le modèle " + candidate.model() + " n'est pas installé : lancez « ollama pull " + candidate.model() + " » sur le serveur.";
        } catch (IOException | IllegalArgumentException exception) {
            return "IA locale injoignable à " + candidate.url() + " : vérifiez l'adresse et que le conteneur Ollama est démarré.";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Vérification interrompue.";
        }
    }

    /**
     * Convertit un document avec le prompt d'un import. Le texte d'un PDF est extrait ici (un scan sans texte est
     * refuse) ; la reponse du modele est ramenee aux lignes du bloc de code.
     */
    public static String convert(String prompt, byte[] document) {
        Settings current = settings;
        if (!current.enabled()) throw new ValidationException("L'IA locale n'est pas activée.");
        String text = PdfText.looksLikePdf(document) ? PdfText.extract(document) : new String(document, StandardCharsets.UTF_8);
        if (text.isBlank()) throw new ValidationException("Ce document ne contient pas de texte lisible (scan ou photo ?) : utilisez un PDF téléchargé depuis l'espace client.");
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        String full = prompt + """

                IMPORTANT : le document est fourni ci-dessous, en texte. Ne pose aucune question et ne demande aucun autre document : produis directement \
                les lignes au format demandé, dans un seul bloc de code, sans commentaire.

                DOCUMENT :
                """ + text;
        String body = "{\"model\":" + json(current.model()) + ",\"prompt\":" + json(full)
                + ",\"stream\":false,\"options\":{\"temperature\":0,\"num_ctx\":16384}}";
        HttpResponse<String> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(URI.create(current.url() + "/api/generate")).timeout(Duration.ofMinutes(15))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ValidationException("L'IA locale ne répond pas. Réessayez, ou copiez le prompt dans un assistant.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ValidationException("Analyse interrompue.");
        }
        if (response.statusCode() == 404) throw new ValidationException("Le modèle " + current.model() + " n'est pas installé sur l'IA locale (ollama pull).");
        if (response.statusCode() != 200) throw new ValidationException("L'IA locale a renvoyé une erreur (" + response.statusCode() + ").");
        String answer = field(response.body(), "response");
        if (answer == null || answer.isBlank()) throw new ValidationException("L'IA locale n'a rien renvoyé : réessayez avec un document plus court.");
        return lines(answer);
    }

    /** Le contenu du premier bloc de code s'il y en a un, sinon la reponse entiere. */
    static String lines(String answer) {
        Matcher block = Pattern.compile("```[a-zA-Z]*\\s*\\n(.*?)```", Pattern.DOTALL).matcher(answer);
        return (block.find() ? block.group(1) : answer).strip();
    }

    /** Valeur texte d'un champ JSON de premier niveau (echappements JSON decodes). */
    static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL).matcher(json);
        if (!matcher.find()) return null;
        String raw = matcher.group(1);
        StringBuilder out = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (c != '\\' || index + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++index);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(raw.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** Modeles proposes selon la memoire du serveur. */
    public static final List<String> SUGGESTED = List.of("qwen2.5:3b", "qwen2.5:7b", "llama3.2:3b", "mistral:7b");
}
