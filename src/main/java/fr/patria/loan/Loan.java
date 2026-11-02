package fr.patria.loan;

/** Pret rattache a un bien. Tous ses chiffres sont calcules a partir de son echeancier ({@link LoanMath}). */
public record Loan(long id, long propertyId, String name) {
}
