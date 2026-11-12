// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.household;

import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.LocalPath;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Administration des membres du foyer (administrateur) et de leurs revenus (utilisateurs, selon leurs vues). */
public final class HouseholdController {
    /** Comptes de connexion lies a chaque membre d'un foyer : identifiants par membre, fournis par l'assemblage. */
    @FunctionalInterface
    public interface Logins {
        Map<Long, List<String>> byMember(long householdId);
    }

    private final HouseholdRepository household;
    private final Clock clock;
    private final Logins logins;

    public HouseholdController(HouseholdRepository household, Clock clock, Logins logins) {
        this.household = household;
        this.clock = clock;
        this.logins = logins;
    }

    public void register(Router router) {
        // Ancienne page « Foyer et revenus », scindee en Membres et Revenus.
        router.userGet("/administration/foyer", request -> Response.redirect("/administration/revenus"));
        // Les membres se gerent dans l'administration (tableau des droits du foyer).
        router.userGet("/administration/membres", request -> Response.redirect("/administration/revenus"));
        router.userGet("/administration/revenus", this::page);
        router.userPost("/administration/revenus", this::addIncome);
        router.userPost("/administration/revenus/import", this::previewIncomes);
        router.userPost("/administration/revenus/import/valider", this::importIncomes);
        router.userGet("/revenu/{id}", this::editForm);
        router.userPost("/revenu/{id}", this::update);
        router.userPost("/revenu/{id}/supprimer", request -> {
            household.deleteIncome(require(request).id());
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/revenus?ok=supprime");
        });
    }

    private Visibility visibility(Request request) {
        return Visibility.of(request.viewer(), household.people());
    }

    private Response page(Request request) {
        return Response.page(HouseholdPages.incomes(visibility(request), household.incomes(), Map.of("start", LocalDate.now(clock).withDayOfMonth(1).toString()),
                request.confirmation(), request.csrf()));
    }

    private Response addIncome(Request request) {
        try {
            household.addIncome(read(request.form(), 0, visibility(request).people()));
            return Response.redirect("/administration/revenus?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.incomes(visibility(request), household.incomes(), request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    /** Revenus colles (reponse d'une IA) ou deposes : apercu avec une case par ligne. */
    private Response previewIncomes(Request request) {
        try {
            String content = new String(request.form().uploadedText("csv", "Fichier CSV"), java.nio.charset.StandardCharsets.UTF_8);
            List<IncomeCsv.Row> rows = IncomeCsv.parse(content, visibility(request).people(), household.incomes());
            return Response.page(HouseholdPages.importPreview(rows, content, importBack(request), request.csrf()));
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.incomes(visibility(request), household.incomes(), Map.of(), Notice.error("Import impossible. " + exception.getMessage()),
                    request.csrf()));
        }
    }

    /** Le contenu est relu et reverifie ; seules les lignes cochees sont enregistrees. */
    private Response importIncomes(Request request) {
        try {
            String content = new String(java.util.Base64.getUrlDecoder().decode(request.form().raw("contenu")), java.nio.charset.StandardCharsets.UTF_8);
            List<IncomeCsv.Row> rows = IncomeCsv.parse(content, visibility(request).people(), household.incomes());
            int added = 0;
            for (IncomeCsv.Row row : rows) {
                if (!row.fresh() || !"on".equals(request.form().raw("importer_" + row.line()))) continue;
                household.addIncome(row.income());
                added++;
            }
            if (added == 0) throw new ValidationException("Cochez au moins un revenu à importer.");
            String back = importBack(request);
            return Response.redirect(back != null ? LocalPath.withOk(back, "importe") : "/administration/revenus?ok=importe");
        } catch (IllegalArgumentException exception) {
            return Response.invalid(HouseholdPages.incomes(visibility(request), household.incomes(), Map.of(), Notice.error("Import impossible : " + exception.getMessage()),
                    request.csrf()));
        }
    }

    /** Import lance depuis une etape du guide : on y revient apres (champ {@code retour}). */
    private static String importBack(Request request) {
        return LocalPath.assistant(request.form().raw("retour"));
    }

    private Response editForm(Request request) {
        Income income = visible(request);
        Visibility visibility = visibility(request);
        boolean readOnly = !visibility.sees(income.personId());
        return Response.page(HouseholdPages.edit(income, readOnly ? visibility.everyone() : visibility.people(), HouseholdPages.values(income), null, back(request),
                readOnly, request.csrf()));
    }

    private Response update(Request request) {
        Income current = require(request);
        try {
            Income income = read(request.form(), current.id(), visibility(request).people());
            java.time.LocalDate from = request.form().raw("effet").isBlank() ? null : request.form().date("effet", "À partir du");
            boolean changed = income.amount().compareTo(current.amount()) != 0 || income.frequency() != current.frequency();
            if (changed && from != null && from.isAfter(current.start()) && (current.end() == null || !from.isAfter(current.end()))) {
                // Revenu change a une date : l'ancien s'arrete la veille (le pouvoir d'achat compare les deux).
                household.updateIncome(new Income(current.id(), current.personId(), current.label(), current.kind(), current.amount(), current.frequency(),
                        current.start(), from.minusDays(1)));
                household.addIncome(new Income(0, income.personId(), income.label(), income.kind(), income.amount(), income.frequency(), from, income.end()));
            } else {
                household.updateIncome(income);
            }
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/revenus?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.edit(current, visibility(request).people(), request.form().values(), Notice.error(exception.getMessage()), back(request), false, request.csrf()));
        }
    }

    /** Retour au budget quand le revenu y a ete ouvert ({@code ?retour=/budget...}). */
    private static String back(Request request) {
        String back = LocalPath.safe(request.query("retour"));
        return back != null && back.startsWith("/budget") ? back : null;
    }

    private static Income read(FormData form, long id, List<Person> people) {
        long personId;
        try {
            personId = Long.parseLong(form.raw("person"));
        } catch (NumberFormatException exception) {
            throw new ValidationException("Personne invalide.");
        }
        if (people.stream().noneMatch(person -> person.id() == personId)) throw new ValidationException("Personne invalide.");
        Frequency frequency = Frequency.parse(form.raw("frequency"));
        if (!HouseholdPages.INCOME_FREQUENCIES.contains(frequency)) throw new ValidationException("Fréquence invalide.");
        LocalDate start = form.date("start", "Premier versement");
        LocalDate end = form.raw("end").isBlank() ? null : form.date("end", "Fin");
        if (end != null && end.isBefore(start)) throw new ValidationException("La fin ne peut pas précéder le premier versement.");
        return new Income(id, personId, form.requiredText("label", "Libellé", 100), IncomeKind.parse(form.raw("kind")),
                form.positiveAmount("amount", "Montant"), frequency, start, end);
    }

    /** Revenu que la personne connectee peut modifier : celui d'un membre dont la vue est ouverte. */
    private Income require(Request request) {
        Income income = visible(request);
        visibility(request).requireEdit(visibility(request).sees(income.personId()));
        return income;
    }

    /** Revenu visible : d'un membre ouvert, ou tous avec la vue « Foyer ». */
    private Income visible(Request request) {
        Income income = household.income(request.id()).orElseThrow(() -> new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas."));
        if (!visibility(request).canSeeAny(income.personId())) throw new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas.");
        return income;
    }
}
