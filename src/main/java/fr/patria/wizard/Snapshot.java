package fr.patria.wizard;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.Category;
import fr.patria.expense.Expense;
import fr.patria.expense.ExpenseKind;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.household.Person;
import fr.patria.household.Transfer;
import fr.patria.loan.Loan;
import fr.patria.property.Property;
import fr.patria.rent.Occupancy;
import fr.patria.rent.RentPeriod;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Ce que le foyer contient deja : sert aux coches automatiques et aux etapes deja faites. */
record Snapshot(List<Person> people, List<Income> incomes, List<BudgetItem> items, List<Property> properties, List<Loan> loans,
                List<Expense> charges, List<RentPeriod> rents, List<Transfer> transfers) {

    boolean hasIncome(IncomeKind kind) {
        return incomes.stream().anyMatch(income -> income.kind() == kind);
    }

    boolean hasItem(Category category) {
        return items.stream().anyMatch(item -> item.category() == category);
    }

    /** Une depense dont le libelle contient l'un de ces mots (sans tenir compte des accents ni des majuscules). */
    boolean hasItemNamed(String... words) {
        return items.stream().anyMatch(item -> {
            String label = normalize(item.label());
            for (String word : words) if (label.contains(normalize(word))) return true;
            return false;
        });
    }

    boolean hasCharge(ExpenseKind kind) {
        return charges.stream().anyMatch(charge -> charge.kind() == kind);
    }

    boolean hasRent(Occupancy kind) {
        return rents.stream().anyMatch(rent -> rent.kind() == kind);
    }

    /** Depense deja saisie avec ce libelle exact (sans accents ni majuscules) : l'assistant ne la repropose pas. */
    java.util.Optional<BudgetItem> item(String label) {
        String wanted = normalize(label);
        return items.stream().filter(item -> normalize(item.label()).equals(wanted)).findFirst();
    }

    /** Toutes les depenses saisies avec ce libelle (lignes saisies une fois par personne). */
    List<BudgetItem> itemsNamed(String label) {
        String wanted = normalize(label);
        return items.stream().filter(item -> normalize(item.label()).equals(wanted)).toList();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }
}
