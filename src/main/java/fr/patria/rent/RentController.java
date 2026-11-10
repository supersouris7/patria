// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.rent;

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

/** Saisie, modification et suppression des periodes d'occupation (location ou residence principale). */
public final class RentController {
    private final RentRepository rents;
    private final PropertyRepository properties;
    private final Clock clock;

    public RentController(RentRepository rents, PropertyRepository properties, Clock clock) {
        this.rents = rents;
        this.properties = properties;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/bien/{id}/loyers", this::page);
        router.post("/administration/bien/{id}/loyers", this::create);
        router.get("/loyer/{id}", this::editForm);
        router.post("/loyer/{id}", this::update);
        router.post("/loyer/{id}/supprimer", this::delete);
    }

    private Response page(Request request) {
        Property property = PropertyController.require(properties, request.id());
        return Response.page(RentPages.page(property, rents.forProperty(property.id()), today(), Map.of(), request.confirmation(), request.csrf()));
    }

    private Response create(Request request) {
        Property property = PropertyController.require(properties, request.id());
        try {
            RentPeriod period = read(request.form(), 0, property.id());
            RentMath.requireNoOverlap(rents.forProperty(property.id()), period);
            rents.insert(period);
            return Response.redirect("/administration/bien/" + property.id() + "/loyers?ok=loyer");
        } catch (ValidationException exception) {
            return Response.invalid(RentPages.page(property, rents.forProperty(property.id()), today(), request.form().values(),
                    Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response editForm(Request request) {
        RentPeriod period = require(request.id());
        return Response.page(RentPages.edit(propertyOf(period), period, RentPages.values(period), null, request.csrf()));
    }

    private Response update(Request request) {
        RentPeriod current = require(request.id());
        try {
            RentPeriod period = read(request.form(), current.id(), current.propertyId());
            RentMath.requireNoOverlap(rents.forProperty(current.propertyId()), period);
            rents.update(period);
            return Response.redirect("/administration/bien/" + current.propertyId() + "/loyers?ok=loyer");
        } catch (ValidationException exception) {
            return Response.invalid(RentPages.edit(propertyOf(current), current, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response delete(Request request) {
        RentPeriod period = require(request.id());
        rents.delete(period.id());
        return Response.redirect("/administration/bien/" + period.propertyId() + "/loyers?ok=supprime");
    }

    private static RentPeriod read(FormData form, long id, long propertyId) {
        LocalDate start = form.date("start", "Début");
        LocalDate end = form.raw("end").isBlank() ? null : form.date("end", "Fin");
        if (end != null && end.isBefore(start)) throw new ValidationException("La fin ne peut pas précéder le début.");
        return new RentPeriod(id, propertyId, Occupancy.parse(form.raw("kind")), form.optionalText("label", "Commentaire", 100), start, end,
                form.positiveAmount("monthly", "Loyer mensuel"));
    }

    private RentPeriod require(long id) {
        return rents.find(id).orElseThrow(() -> new NotFoundException("Période introuvable", "Cette période d'occupation n'existe pas."));
    }

    private Property propertyOf(RentPeriod period) {
        return PropertyController.require(properties, period.propertyId());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
