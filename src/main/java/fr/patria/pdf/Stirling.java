package fr.patria.pdf;

import fr.patria.common.ValidationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Client du serveur Stirling PDF de l'installation (API REST). Patria garde toute l'interface ; Stirling sert de moteur :
 * OCR, caviardage par expressions, nettoyage des metadonnees. Le document ne fait que transiter en memoire : rien n'est
 * stocke. Remplacable par un autre moteur sans toucher aux pages.
 *
 * <p>Champs et chemins verifies dans le code de Stirling PDF (RedactPdfRequest, ProcessPdfWithOcrRequest,
 * SanitizePdfRequest). Les appels avec cle API consomment l'allocation de traitement du serveur (402 si epuisee).
 */
public final class Stirling {
    private static volatile Stirling current;

    private final URI base;
    private final String apiKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Reperes courants proposes au caviardage : identifiants qu'un document financier contient presque toujours. */
    public enum Preset {
        IBAN("IBAN et RIB", "\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,3})?\\b"),
        CARD("Numéros de carte bancaire", "\\b(?:\\d{4}[ -]?){3}\\d{4}\\b"),
        EMAIL("Adresses e-mail", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        PHONE("Numéros de téléphone", "(?:\\+33[ .]?|\\b0)[1-9](?:[ .-]?\\d{2}){4}\\b"),
        SOCIAL_SECURITY("Numéro de sécurité sociale", "\\b[12][ ]?\\d{2}[ ]?(?:0[1-9]|1[0-2]|[2-9]\\d)[ ]?(?:\\d{2}|2A|2B)[ ]?\\d{3}[ ]?\\d{3}(?:[ ]?\\d{2})?\\b"),
        TAX_NUMBER("Numéro fiscal", "\\b[0-3]\\d(?:[ ]?\\d){11}\\b"),
        LONG_NUMBERS("Numéros longs (compte, contrat, client, RUM)", "\\b[A-Z0-9]*\\d{8,}[A-Z0-9]*\\b");

        private final String label;
        private final String regex;

        Preset(String label, String regex) {
            this.label = label;
            this.regex = regex;
        }

        public String label() {
            return label;
        }

        public String regex() {
            return regex;
        }
    }

    /**
     * Ce qu'il faut faire du document.
     *
     * @param words   mots a masquer tels quels (noms, adresses...), sans tenir compte de la casse
     * @param presets reperes courants a masquer
     * @param ocr     document scanne : reconnaitre le texte avant de chercher
     * @param flatten transformer les pages en images : le texte masque ne peut pas etre recupere
     */
    public record Options(List<String> words, List<Preset> presets, boolean ocr, boolean flatten) {
        /** Une expression par ligne : les mots echappes et bornes (lettres accentuees comprises), puis les reperes. */
        public List<String> expressions() {
            List<String> result = new ArrayList<>();
            for (String word : words) {
                String trimmed = word.strip();
                if (trimmed.length() >= 2) result.add("(?<![\\p{L}\\p{N}])" + escape(trimmed).replace(" ", "\\s+") + "(?![\\p{L}\\p{N}])");
            }
            presets.forEach(preset -> result.add(preset.regex()));
            return result;
        }
    }

    /** Le document prepare et le nombre de zones masquees ({@code -1} si le serveur ne le dit pas). */
    public record Result(byte[] pdf, int matches) {
    }

    Stirling(URI base, String apiKey) {
        this.base = base;
        this.apiKey = apiKey;
    }

    /** Serveur de l'installation ({@code null} : outil masque). Appele au demarrage. */
    public static void configure(String url, String apiKey) {
        current = url == null ? null : new Stirling(URI.create(url + "/"), apiKey);
    }

    /** L'outil « Preparer un document » est propose. */
    public static boolean available() {
        return current != null;
    }

    public static Stirling current() {
        return current;
    }

    /** OCR (facultatif), caviardage, puis nettoyage (metadonnees, scripts, pieces jointes, liens). */
    public Result prepare(byte[] pdf, Options options) {
        byte[] document = pdf;
        if (options.ocr()) {
            document = call("api/v1/misc/ocr-pdf", document, List.of(Map.entry("languages", "fra"), Map.entry("languages", "eng"),
                    Map.entry("ocrType", "skip-text"), Map.entry("ocrRenderType", "hocr"))).body();
        }
        int matches = -1;
        List<String> expressions = options.expressions();
        if (!expressions.isEmpty()) {
            HttpResponse<byte[]> redacted = call("api/v1/security/auto-redact", document, List.of(
                    Map.entry("listOfText", String.join("\n", expressions)), Map.entry("useRegex", "true"), Map.entry("wholeWordSearch", "false"),
                    Map.entry("caseSensitive", "false"), Map.entry("redactColor", "#000000"), Map.entry("customPadding", "0"),
                    Map.entry("convertPDFToImage", String.valueOf(options.flatten()))));
            document = redacted.body();
            matches = redacted.headers().firstValue("X-Stirling-Redaction-Matches").map(Stirling::number).orElse(-1);
        }
        document = call("api/v1/security/sanitize-pdf", document, List.of(Map.entry("removeJavaScript", "true"), Map.entry("removeEmbeddedFiles", "true"),
                Map.entry("removeXMPMetadata", "true"), Map.entry("removeMetadata", "true"), Map.entry("removeLinks", "true"),
                Map.entry("removeFonts", "false"))).body();
        return new Result(document, matches);
    }

    private HttpResponse<byte[]> call(String path, byte[] pdf, List<Map.Entry<String, String>> fields) {
        String boundary = "patria-" + UUID.randomUUID();
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMinutes(3))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, pdf, fields)));
        if (apiKey != null) request.header("X-API-KEY", apiKey);
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ValidationException("Le serveur Stirling PDF ne répond pas. Réessayez plus tard ou prévenez l'administrateur.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ValidationException("Traitement interrompu.");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) throw new ValidationException("Stirling PDF refuse la clé API : l'administrateur doit vérifier STIRLING_API_KEY.");
        if (status == 402) throw new ValidationException("Stirling PDF : l'allocation de traitement du serveur est épuisée. Prévenez l'administrateur.");
        if (status == 413) throw new ValidationException("Document trop gros pour le serveur Stirling PDF.");
        if (status / 100 != 2) throw new ValidationException("Stirling PDF n'a pas pu traiter ce document (erreur " + status + "). Est-ce bien un PDF lisible ?");
        return response;
    }

    static byte[] multipart(String boundary, byte[] pdf, List<Map.Entry<String, String>> fields) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields) {
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n");
        }
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"fileInput\"; filename=\"document.pdf\"\r\nContent-Type: application/pdf\r\n\r\n");
        body.writeBytes(pdf);
        write(body, "\r\n--" + boundary + "--\r\n");
        return body.toByteArray();
    }

    private static void write(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String escape(String text) {
        return text.replaceAll("[\\\\.^$|?*+()\\[\\]{}]", "\\\\$0");
    }

    /** Un PDF commence par « %PDF- » : refuse tout autre fichier avant de l'envoyer. */
    public static boolean looksLikePdf(byte[] content) {
        return content != null && content.length > 5 && new String(content, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    static Pattern compiled(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
