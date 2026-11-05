package fr.patria.budget;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.loan.CsvScheduleParser;
import org.junit.jupiter.api.Test;

class LlmPromptTest {
    @Test
    void expensePromptListsTheAcceptedValues() {
        String prompt = ExpenseCsv.llmPrompt();
        assertTrue(prompt.contains("SANS ligne d'en-tête"));
        assertTrue(prompt.contains("Impôts et taxes") && prompt.contains("Santé"), "catégories");
        assertTrue(!prompt.contains("Virements,"), "pas la catégorie interne des virements");
        assertTrue(prompt.contains("Mensuel") && prompt.contains("Ponctuel"), "fréquences");
        assertTrue(prompt.contains("ne recopie jamais") && prompt.contains("IBAN") && prompt.contains("numéro fiscal") && prompt.contains("sécurité sociale"),
                "consignes d'anonymisation");
    }

    @Test
    void schedulePromptGivesTheExactHeader() {
        String prompt = CsvScheduleParser.llmPrompt();
        assertTrue(prompt.contains("date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct"));
        assertTrue(prompt.contains("REMBOURSEMENT_ANTICIPE"));
        assertTrue(prompt.contains("ne recopie jamais le nom ou l'adresse de l'emprunteur"), "consignes d'anonymisation");
    }
}
