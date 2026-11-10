// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codes a usage unique des applications d'authentification (TOTP, RFC 6238 : HMAC-SHA1, 6 chiffres, toutes les
 * 30 secondes). Fonctions pures, sans service exterieur.
 */
public final class Totp {
    public static final int STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** Nouveau secret de 160 bits, en base32 (ce que l'application d'authentification enregistre). */
    public static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /** Pas de temps (numero de la periode de 30 s) d'un instant en secondes. */
    public static long step(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** Code a 6 chiffres d'un pas de temps. */
    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Pas de temps du code saisi, s'il est juste (periode actuelle, precedente ou suivante : horloges un peu
     * decalees) et posterieur a {@code lastUsed} (un code ne sert qu'une fois) ; -1 sinon.
     */
    public static long verify(String secret, String entered, long epochSeconds, long lastUsed) {
        String code = entered == null ? "" : entered.replaceAll("[\\s-]", "");
        if (!code.matches("\\d{6}")) return -1;
        long now = step(epochSeconds);
        for (long candidate = now - 1; candidate <= now + 1; candidate++) {
            if (candidate > lastUsed && MessageDigest.isEqual(code(secret, candidate).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII))) {
                return candidate;
            }
        }
        return -1;
    }

    /** Adresse {@code otpauth://} du QR code a scanner. */
    public static String uri(String issuer, String account, String secret) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    /** Secret groupe par 4 pour la saisie a la main : « ABCD EFGH … ». */
    public static String grouped(String secret) {
        return secret.replaceAll("(.{4})(?!$)", "$1 ");
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] decode(String secret) {
        String clean = secret.replaceAll("[\\s=]", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("Secret invalide.");
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
