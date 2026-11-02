package fr.patria.web;

import fr.patria.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Lecture des formulaires {@code multipart/form-data} (envoi de fichiers CSV). */
final class MultipartParser {
    private MultipartParser() {
    }

    static FormData parse(String contentType, byte[] body) {
        String boundary = boundary(contentType);
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] first = ("--" + boundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        if (!startsWith(body, first, 0)) throw new ValidationException("Corps multipart invalide.");
        Map<String, String> values = new HashMap<>();
        Map<String, byte[]> files = new HashMap<>();
        int start = first.length;
        while (start < body.length) {
            int headersEnd = indexOf(body, headerEnd, start);
            if (headersEnd < 0) throw new ValidationException("Partie multipart invalide.");
            String headers = new String(body, start, headersEnd - start, StandardCharsets.ISO_8859_1);
            String disposition = Arrays.stream(headers.split("\r\n"))
                    .filter(header -> header.toLowerCase(Locale.ROOT).startsWith("content-disposition:")).findFirst().orElse("");
            String name = dispositionValue(disposition, "name");
            if (name == null || name.length() > 32) throw new ValidationException("Partie multipart invalide.");
            int contentStart = headersEnd + headerEnd.length;
            int next = indexOf(body, delimiter, contentStart);
            if (next < 0) throw new ValidationException("Fin multipart manquante.");
            byte[] content = Arrays.copyOfRange(body, contentStart, next);
            if (dispositionValue(disposition, "filename") == null) values.put(name, new String(content, StandardCharsets.UTF_8));
            else files.put(name, content);
            start = next + delimiter.length;
            if (startsWith(body, "--".getBytes(StandardCharsets.ISO_8859_1), start)) break;
            if (!startsWith(body, "\r\n".getBytes(StandardCharsets.ISO_8859_1), start)) throw new ValidationException("Séparateur multipart invalide.");
            start += 2;
        }
        return new FormData(values, files);
    }

    private static String boundary(String contentType) {
        int at = contentType.indexOf("boundary=");
        if (at < 0) throw new ValidationException("Frontière multipart manquante.");
        String boundary = contentType.substring(at + "boundary=".length()).trim();
        int semicolon = boundary.indexOf(';');
        if (semicolon >= 0) boundary = boundary.substring(0, semicolon).trim();
        if (boundary.length() > 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);
        if (boundary.isBlank() || boundary.length() > 70 || !boundary.matches("[0-9A-Za-z'()+_,./:=?-]+")) throw new ValidationException("Frontière multipart invalide.");
        return boundary;
    }

    private static String dispositionValue(String disposition, String key) {
        String prefix = " " + key + "=\"";
        int start = disposition.indexOf(prefix);
        if (start < 0) {
            prefix = ";" + key + "=\"";
            start = disposition.indexOf(prefix);
        }
        if (start < 0) return null;
        int end = disposition.indexOf('"', start + prefix.length());
        return end < 0 ? null : disposition.substring(start + prefix.length(), end);
    }

    private static int indexOf(byte[] source, byte[] target, int from) {
        outer:
        for (int i = from; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (source[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] source, byte[] target, int at) {
        if (at + target.length > source.length) return false;
        for (int i = 0; i < target.length; i++) if (source[at + i] != target[i]) return false;
        return true;
    }
}
