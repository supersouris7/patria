package fr.patria.expense;

import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/** Saisie et suppression des charges annuelles d'un bien. */
public final class ExpenseController {
    private final ExpenseRepository expenses;
    private final PropertyRepository properties;
    private final Clock clock;

    public ExpenseController(ExpenseRepository expenses, PropertyRepository properties, Clock clock) {
        this.expenses = expenses;
        this.properties = properties;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/bien/{id}/charges", this::page);
        router.post("/administration/bien/{id}/charges", this::save);
        router.post("/charge/{id}/supprimer", this::delete);
    }

    private Response page(Request request) {
        Property property = PropertyController.require(properties, request.id());
        LocalDate today = LocalDate.now(clock);
        return Response.page(ExpensePages.page(property, expenses.forProperty(property.id()), today, Map.of("year", String.valueOf(today.getYear())),
                request.confirmation(), request.csrf()));
    }

    private Response save(Request request) {
        Property property = PropertyController.require(properties, request.id());
        LocalDate today = LocalDate.now(clock);
        try {
            expenses.save(read(request.form(), property.id(), today));
            return Response.redirect("/administration/bien/" + property.id() + "/charges?ok=charge");
        } catch (ValidationException exception) {
            return Response.invalid(ExpensePages.page(property, expenses.forProperty(property.id()), today, request.form().values(),
                    Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response delete(Request request) {
        Expense expense = expenses.find(request.id()).orElseThrow(() -> new NotFoundException("Charge introuvable", "Cette charge n'existe pas."));
        expenses.delete(expense.id());
        return Response.redirect("/administration/bien/" + expense.propertyId() + "/charges?ok=supprime");
    }

    private static Expense read(FormData form, long propertyId, LocalDate today) {
        int year;
        try {
            year = Integer.parseInt(form.raw("year").trim());
        } catch (NumberFormatException exception) {
            throw new ValidationException("Année invalide.");
        }
        if (year < 1950 || year > today.getYear() + 1) throw new ValidationException("Année invalide.");
        return new Expense(0, propertyId, ExpenseKind.parse(form.raw("kind")), year, form.positiveAmount("amount", "Montant annuel"));
    }
}
