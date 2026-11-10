// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ai;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import fr.patria.common.ValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Texte d'un PDF (releve, avis, export), page par page, avec OpenPDF. Un scan n'a pas de texte : resultat vide. */
public final class PdfText {
    static final int MAX_PAGES = 60;

    private PdfText() {
    }

    public static boolean looksLikePdf(byte[] content) {
        return content != null && content.length > 5 && new String(content, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");
    }

    public static String extract(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            try {
                if (reader.isEncrypted() && !reader.isOpenedWithFullPermissions()) throw new ValidationException("Ce PDF est protégé : enregistrez-en une copie sans mot de passe.");
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                StringBuilder text = new StringBuilder();
                int pages = Math.min(reader.getNumberOfPages(), MAX_PAGES);
                for (int page = 1; page <= pages; page++) {
                    text.append(extractor.getTextFromPage(page)).append("\n\n");
                }
                return text.toString().strip();
            } finally {
                reader.close();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ValidationException validation) throw validation;
            throw new ValidationException("PDF illisible : vérifiez le fichier.");
        }
    }
}
