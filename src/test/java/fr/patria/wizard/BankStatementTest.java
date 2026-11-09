package fr.patria.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.household.Person;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankStatementTest {
    private static final Person PAUL = new Person(1, "Paul");

    @Test
    void csvExportsBecomeRecurringLines() {
        // CSV a la francaise : point-virgule, virgule decimale, debit et credit separes, ligne de titre au-dessus
        String csv = """
                Compte courant de Paul
                Date opération;Libellé;Débit;Crédit
                05/09/2026;PRLV SEPA FREE MOBILE FACT 0926;19,99;
                05/10/2026;PRLV SEPA FREE MOBILE FACT 1026;19,99;
                05/11/2026;PRLV SEPA FREE MOBILE FACT 1126;19,99;
                28/09/2026;VIR SALAIRE ACME;;2450,00
                28/10/2026;VIR SALAIRE ACME;;2450,00
                12/10/2026;CB CARREFOUR 12/10;84,20;
                19/10/2026;CB CARREFOUR 19/10;120,15;
                02/11/2026;CB CARREFOUR 02/11;61,70;
                """;
        List<BankStatement.Transaction> read = BankStatement.read(csv).orElseThrow();
        assertEquals(8, read.size());
        assertEquals(new BigDecimal("-19.99"), read.getFirst().amount());
        List<String> lines = BankStatement.recurring(read, PAUL);
        assertTrue(lines.contains("DEPENSE;Free Mobile;Télécom et abonnements;19,99;Mensuel;05/11/2026;;;Relevé bancaire"), lines.toString());
        assertTrue(lines.contains("REVENU;Paul;Salaire Acme;Salaire net;2450,00;Mensuel;28/10/2026;"), lines.toString());
        assertEquals(2, lines.size(), "les courses (montants variables) ne sont pas devinées");
        assertTrue(BankStatement.read("REVENU;Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;").isEmpty(), "une réponse d'IA passe telle quelle");
    }

    @Test
    void ofxAndQifAreRead() {
        String ofx = """
                OFXHEADER:100
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260910<TRNAMT>-45.30<NAME>PRLV EDF CLIENTS</STMTTRN>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261010<TRNAMT>-45.30<NAME>PRLV EDF CLIENTS</STMTTRN>
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>""";
        List<BankStatement.Transaction> fromOfx = BankStatement.read(ofx).orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 10), fromOfx.getFirst().date());
        assertTrue(BankStatement.recurring(fromOfx, PAUL).getFirst().startsWith("DEPENSE;Edf Clients;Eau et énergie;45,30;Mensuel;10/10/2026"));
        String qif = """
                !Type:Bank
                D15/09/2026
                T-12,00
                PNETFLIX.COM
                ^
                D15/10/2026
                T-12,00
                PNETFLIX.COM
                ^
                """;
        assertTrue(BankStatement.recurring(BankStatement.read(qif).orElseThrow(), PAUL).getFirst().startsWith("DEPENSE;Netflix Com;Télécom et abonnements;12,00"));
        assertEquals("Crédits", BankStatement.category("Credit Agricole Pret"), "« red » de « credit » n'est pas Red by SFR");
        String windows = fr.patria.common.CsvText.decode("Date;Libellé;Montant".getBytes(java.nio.charset.Charset.forName("windows-1252")));
        assertEquals("Date;Libellé;Montant", windows, "export bancaire en Windows-1252");
    }
}
