package fr.patria.ui;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rapport de synthese en PDF (A4), commun a toutes les pages de consultation : en-tete (foyer, titre, vue, date
 * d'edition), chiffres cles, sections, tableaux et notes, numero de page en pied. Polices standard (Helvetica), sans
 * fichier a embarquer ; les caracteres hors Windows-1252 (espace fine, signe moins) sont remplaces.
 *
 * <p>Usage : {@code PdfReport.create("Prévisionnel", "Budget du foyer", today).metrics(...).section("...").table(...).toBytes()}.
 */
public final class PdfReport {
    /** Chiffre cle : libelle, valeur, precision facultative ; {@code tone} colore la valeur. */
    public record Metric(String label, String value, String sub, Tone tone) {
        public static Metric of(String label, String value, String sub) {
            return new Metric(label, value, sub, Tone.NEUTRAL);
        }
    }

    public enum Tone { NEUTRAL, POSITIVE, NEGATIVE }

    private static final Color TEXT = new Color(0x1b, 0x24, 0x30);
    private static final Color MUTED = new Color(0x5f, 0x6b, 0x7a);
    private static final Color BLUE = new Color(0x1a, 0x5f, 0xb4);
    private static final Color LINE = new Color(0xd9, 0xde, 0xe5);
    private static final Color SOFT = new Color(0xf3, 0xf5, 0xf8);
    private static final Color GREEN = new Color(0x1f, 0x7a, 0x3a);
    private static final Color RED = new Color(0xb4, 0x2a, 0x37);
    private static final BaseFont REGULAR = font(BaseFont.HELVETICA);
    private static final BaseFont BOLD = font(BaseFont.HELVETICA_BOLD);

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    /** Titre de section en attente : il est place en tete du tableau qui suit, pour ne jamais rester seul en bas de page. */
    private String pendingSection;
    private final Document document = new Document(PageSize.A4, 40, 40, 44, 50);

    private PdfReport(String title, String subtitle, LocalDate edited) {
        try {
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new Footer());
            document.addTitle(clean(title));
            document.addCreator("Patria");
            document.open();
            Layout.Chrome chrome = Layout.CHROME.get();
            String household = chrome == null ? "" : chrome.households().stream().filter(choice -> choice.id() == chrome.household())
                    .map(Layout.Choice::name).findFirst().orElse("");
            add(paragraph("PATRIA" + (household.isEmpty() ? "" : "  ·  " + household.toUpperCase(java.util.Locale.FRENCH)), font(BOLD, 8, BLUE), 0, 2));
            add(paragraph(title, font(BOLD, 20, TEXT), 0, 2));
            String meta = (subtitle == null || subtitle.isBlank() ? "" : subtitle + "  ·  ") + "Édité le " + Format.date(edited);
            add(paragraph(meta, font(REGULAR, 9.5f, MUTED), 0, 12));
        } catch (DocumentException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static PdfReport create(String title, String subtitle, LocalDate edited) {
        return new PdfReport(title, subtitle, edited);
    }

    /** Rangee de chiffres cles (quatre au plus par ligne). */
    public PdfReport metrics(List<Metric> metrics) {
        if (metrics.isEmpty()) return this;
        flushSection();
        int columns = Math.min(4, metrics.size());
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);
        for (Metric metric : metrics) {
            PdfPCell cell = new PdfPCell();
            cell.setBorderColor(LINE);
            cell.setBackgroundColor(SOFT);
            cell.setPadding(7);
            cell.setPaddingBottom(9);
            cell.addElement(paragraph(metric.label().toUpperCase(java.util.Locale.FRENCH), font(BOLD, 6.8f, MUTED), 0, 2));
            Color color = metric.tone() == Tone.POSITIVE ? GREEN : metric.tone() == Tone.NEGATIVE ? RED : TEXT;
            cell.addElement(paragraph(metric.value(), font(BOLD, 13, color), 0, 1));
            if (metric.sub() != null && !metric.sub().isBlank()) cell.addElement(paragraph(metric.sub(), font(REGULAR, 7.5f, MUTED), 0, 0));
            table.addCell(cell);
        }
        for (int rest = metrics.size() % columns; rest != 0 && rest < columns; rest++) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            table.addCell(empty);
        }
        add(table);
        return this;
    }

    /** Titre de section. */
    public PdfReport section(String title) {
        flushSection();
        pendingSection = title;
        return this;
    }

    private void flushSection() {
        if (pendingSection == null) return;
        String title = pendingSection;
        pendingSection = null;
        add(paragraph(title, font(BOLD, 11.5f, TEXT), 10, 5));
    }

    /** Ligne de titre de section en tete d'un tableau (repetee sur chaque page si le tableau continue). */
    private int sectionRow(PdfPTable table, int columns) {
        if (pendingSection == null) return 0;
        PdfPCell cell = new PdfPCell(new Phrase(clean(pendingSection), font(BOLD, 11.5f, TEXT)));
        pendingSection = null;
        cell.setColspan(columns);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(12);
        cell.setPaddingBottom(6);
        cell.setPaddingLeft(0);
        table.addCell(cell);
        return 1;
    }

    public PdfReport paragraph(String text) {
        add(paragraph(text, font(REGULAR, 9, TEXT), 0, 6));
        return this;
    }

    /** Note discrete (source, methode de calcul). */
    public PdfReport note(String text) {
        add(paragraph(text, font(REGULAR, 7.5f, MUTED), 0, 6));
        return this;
    }

    /** Liste a puces ; une ligne commencant par « ! » est mise en avant (alerte importante). */
    public PdfReport bullets(List<String> lines) {
        for (String line : lines) {
            boolean serious = line.startsWith("!");
            Paragraph paragraph = new Paragraph();
            paragraph.setIndentationLeft(10);
            paragraph.setFirstLineIndent(-10);
            paragraph.setSpacingAfter(2);
            paragraph.add(new Chunk(serious ? "!  " : "•  ", font(BOLD, 9, serious ? RED : MUTED)));
            paragraph.add(new Chunk(clean(serious ? line.substring(1).trim() : line), font(serious ? BOLD : REGULAR, 9, serious ? RED : TEXT)));
            add(paragraph);
        }
        add(paragraph(" ", font(REGULAR, 4, TEXT), 0, 0));
        return this;
    }

    /** Lignes libelle / valeur (detail d'un calcul) ; la derniere peut etre un total. */
    public PdfReport lines(List<String[]> rows, boolean lastIsTotal) {
        PdfPTable table = new PdfPTable(new float[]{3, 1.4f});
        table.setWidthPercentage(70);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setSpacingAfter(8);
        table.setKeepTogether(true);
        sectionRow(table, 2);
        for (int index = 0; index < rows.size(); index++) {
            boolean total = lastIsTotal && index == rows.size() - 1;
            Font font = font(total ? BOLD : REGULAR, total ? 10 : 9, TEXT);
            table.addCell(cell(rows.get(index)[0], font, Element.ALIGN_LEFT, total ? Rectangle.TOP : Rectangle.BOTTOM));
            table.addCell(cell(rows.get(index)[1], font, Element.ALIGN_RIGHT, total ? Rectangle.TOP : Rectangle.BOTTOM));
        }
        add(table);
        return this;
    }

    /**
     * Tableau : une colonne dont le nom commence par « # » est numerique (alignee a droite). {@code total} : derniere
     * ligne en gras (vide si aucune).
     */
    public PdfReport table(List<String> columns, List<List<String>> rows, float[] widths, boolean total) {
        if (rows.isEmpty()) {
            note("Aucune ligne.");
            return this;
        }
        PdfPTable table = widths == null ? new PdfPTable(columns.size()) : new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        // Un petit tableau reste d'un bloc ; un long continue sur la page suivante avec son titre et ses colonnes.
        table.setKeepTogether(rows.size() <= 8);
        table.setHeaderRows(1 + sectionRow(table, columns.size()));
        for (String column : columns) {
            boolean number = column.startsWith("#");
            PdfPCell cell = cell((number ? column.substring(1) : column).toUpperCase(java.util.Locale.FRENCH), font(BOLD, 6.8f, MUTED),
                    number ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT, Rectangle.BOTTOM);
            cell.setBorderColor(MUTED);
            table.addCell(cell);
        }
        for (int index = 0; index < rows.size(); index++) {
            boolean bold = total && index == rows.size() - 1;
            List<String> row = rows.get(index);
            for (int column = 0; column < columns.size(); column++) {
                String value = column < row.size() ? row.get(column) : "";
                Font font = font(bold ? BOLD : REGULAR, 8.5f, value.startsWith("-") || value.startsWith("−") ? RED : TEXT);
                if (!bold && value.startsWith("+")) font = font(REGULAR, 8.5f, GREEN);
                PdfPCell cell = cell(value, font, columns.get(column).startsWith("#") ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT,
                        bold ? Rectangle.TOP : Rectangle.BOTTOM);
                if (bold) cell.setBorderColor(MUTED);
                table.addCell(cell);
            }
        }
        add(table);
        return this;
    }

    public byte[] toBytes() {
        flushSection();
        document.close();
        return output.toByteArray();
    }

    // ---- Briques ----

    private void add(Element element) {
        if (!(element instanceof PdfPTable)) flushSection();
        try {
            document.add(element);
        } catch (DocumentException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PdfPCell cell(String text, Font font, int alignment, int border) {
        PdfPCell cell = new PdfPCell(new Phrase(clean(text), font));
        cell.setHorizontalAlignment(alignment);
        cell.setBorder(border);
        cell.setBorderColor(LINE);
        cell.setBorderWidth(0.6f);
        cell.setPaddingTop(3.5f);
        cell.setPaddingBottom(4.5f);
        cell.setPaddingLeft(3);
        cell.setPaddingRight(3);
        return cell;
    }

    private static Paragraph paragraph(String text, Font font, float before, float after) {
        Paragraph paragraph = new Paragraph(clean(text), font);
        paragraph.setSpacingBefore(before);
        paragraph.setSpacingAfter(after);
        paragraph.setLeading(font.getSize() * 1.3f);
        return paragraph;
    }

    private static Font font(BaseFont base, float size, Color color) {
        return new Font(base, size, Font.NORMAL, color);
    }

    private static BaseFont font(String name) {
        try {
            return BaseFont.createFont(name, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (DocumentException | IOException exception) {
            throw new IllegalStateException("Police PDF indisponible : " + name, exception);
        }
    }

    /** Texte compatible Windows-1252 (polices standard du PDF). */
    static String clean(String text) {
        if (text == null) return "";
        return text.replace(' ', ' ').replace('−', '-').replace("→", "->").replace("≈", "~").replace("✓", "")
                .replace("▲", "").replace(' ', ' ');
    }

    /** Numero de page en pied. */
    private static final class Footer extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), font(REGULAR, 7.5f, MUTED)),
                    document.right(), document.bottom() - 22, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase("Patria · synthèse indicative, à vérifier avant toute décision", font(REGULAR, 7.5f, MUTED)),
                    document.left(), document.bottom() - 22, 0);
        }
    }

    /** Lignes de tableau a partir de valeurs deja formatees. */
    public static List<String> row(Object... values) {
        List<String> row = new ArrayList<>();
        for (Object value : values) row.add(value == null ? "" : value.toString());
        return row;
    }
}
