package fr.patria.loan;

import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Creation d'un pret depuis un CSV, consultation, renommage, remplacement de l'echeancier, suppression. */
public final class LoanController {
    private final LoanRepository loans;
    private final PropertyRepository properties;
    private final Clock clock;

    public LoanController(LoanRepository loans, PropertyRepository properties, Clock clock) {
        this.loans = loans;
        this.properties = properties;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/bien/{id}/prets/nouveau", this::newForm);
        router.post("/bien/{id}/prets", this::create);
        router.estateGet("/pret/{id}", this::detail);
        router.get("/pret/{id}/gerer", this::manage);
        router.get("/pret/{id}/echeancier", request -> Response.redirect("/pret/" + request.id() + "/gerer"));
        router.post("/pret/{id}/echeancier", this::replaceSchedule);
        router.post("/pret/{id}/renommer", this::rename);
        router.post("/pret/{id}/supprimer", this::delete);
    }

    private Response newForm(Request request) {
        Property property = PropertyController.require(properties, request.id());
        return Response.page(LoanPages.create(property, Map.of(), null, request.csrf()));
    }

    private Response create(Request request) {
        Property property = PropertyController.require(properties, request.id());
        try {
            String name = request.form().requiredText("name", "Nom du prêt", 100);
            List<ScheduleLine> lines = CsvScheduleParser.parse(request.form().requiredFile("csv", "Fichier CSV"));
            long id = loans.create(property.id(), name, lines);
            return Response.redirect("/pret/" + id + "?ok=echeancier");
        } catch (ValidationException exception) {
            return Response.invalid(LoanPages.create(property, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response detail(Request request) {
        Loan loan = require(request.id());
        List<ScheduleLine> lines = loans.schedule(loan.id());
        LocalDate today = LocalDate.now(clock);
        return Response.page(LoanPages.detail(loan, propertyOf(loan), lines, LoanMath.status(lines, today), today, request.confirmation(), request.csrf()));
    }

    private Response manage(Request request) {
        Loan loan = require(request.id());
        return Response.page(LoanPages.manage(loan, propertyOf(loan), request.confirmation(), request.csrf()));
    }

    private Response replaceSchedule(Request request) {
        Loan loan = require(request.id());
        try {
            loans.replaceSchedule(loan.id(), CsvScheduleParser.parse(request.form().requiredFile("csv", "Fichier CSV")));
            return Response.redirect("/pret/" + loan.id() + "?ok=echeancier");
        } catch (ValidationException exception) {
            return Response.invalid(LoanPages.manage(loan, propertyOf(loan), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response rename(Request request) {
        Loan loan = require(request.id());
        try {
            loans.rename(loan.id(), request.form().requiredText("name", "Nom du prêt", 100));
            return Response.redirect("/pret/" + loan.id() + "?ok=renomme");
        } catch (ValidationException exception) {
            return Response.invalid(LoanPages.manage(loan, propertyOf(loan), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response delete(Request request) {
        Loan loan = require(request.id());
        loans.delete(loan.id());
        return Response.redirect("/administration/bien/" + loan.propertyId() + "/financements?ok=supprime");
    }

    private Loan require(long id) {
        return loans.find(id).orElseThrow(() -> new NotFoundException("Prêt introuvable", "Ce prêt n'existe pas."));
    }

    private Property propertyOf(Loan loan) {
        return PropertyController.require(properties, loan.propertyId());
    }
}
