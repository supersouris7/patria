package fr.patria.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import fr.patria.common.ValidationException;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class LocalAiTest {
    @Test
    void readsJsonAnswersAndKeepsOnlyTheCodeBlock() {
        String json = "{\"model\":\"qwen\",\"response\":\"Voici :\\n```csv\\nA;\\u00e9;1\\nB;\\\"x\\\";2\\n```\\nFin\",\"done\":true}";
        assertEquals("A;é;1\nB;\"x\";2", LocalAi.lines(LocalAi.field(json, "response")));
        assertEquals("\"a\\\"b\\nc\"", LocalAi.json("a\"b\nc"));
    }

    @Test
    void extractsTheTextOfAPdfAndRefusesNonsense() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Relevé : PRLV NETFLIX 13,49"));
        document.close();
        byte[] pdf = out.toByteArray();
        assertTrue(PdfText.looksLikePdf(pdf));
        assertTrue(PdfText.extract(pdf).contains("NETFLIX"));
        assertThrows(ValidationException.class, () -> PdfText.extract("%PDF-cassé".getBytes()));
    }

    @Test
    void settingsAreValidated() {
        assertEquals(LocalAi.Settings.DEFAULT_URL, new LocalAi.Settings(true, " ", null).url());
        assertThrows(ValidationException.class, () -> new LocalAi.Settings(true, "ftp://x", "m"));
        assertThrows(ValidationException.class, () -> LocalAi.convert("prompt", "texte".getBytes()), "désactivée par défaut");
    }
}
