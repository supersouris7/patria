// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import static fr.patria.ui.Format.euroWhole;
import static fr.patria.wizard.WizardPages.*;

import fr.patria.expense.ExpenseKind;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.loan.CsvScheduleParser;
import fr.patria.property.Property;
import fr.patria.property.PropertyKind;
import fr.patria.rent.Occupancy;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Etapes de l'immobilier du guide : biens, prets, charges et loyers, repartition. HTML uniquement. */
final class WizardEstatePages {
    private WizardEstatePages() {
    }

    static Html properties(WizardController.Context context, Map<String, String> values) {
        List<Property> properties = context.snapshot().properties();
        Html list = properties.isEmpty() ? Html.raw("<p class=\"empty\">Aucun bien pour l'instant.</p>")
                : Html.of("<ul class=\"done-list\">%s</ul>", properties.stream().map(property -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s · %s%s</li>",
                property.name(), property.kind().label().toLowerCase(java.util.Locale.FRENCH), property.commune().isBlank() ? "" : " · " + property.commune())).collect(Html.joining()));
        Map<String, String> defaults = values.isEmpty() ? Map.of("purchase_date", context.today().toString(), "estimate_date", context.today().toString(), "sale_fees_pct", "8") : values;
        Html add = form(context, "/assistant/biens/ajouter", false, Components.fields(3,
                Components.input(Input.of("Nom du bien", "name", Field.TEXT).required().maxLength(100).placeholder("Appartement Lyon"), defaults),
                Components.select("Type", "kind", Arrays.stream(PropertyKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                        defaults.getOrDefault("kind", PropertyKind.APARTMENT.name())),
                Components.input(Input.of("Commune", "commune", Field.TEXT).maxLength(100).help("Sert au prix du marché (ventes du secteur)."), defaults),
                Components.input(Input.of("Adresse", "address", Field.TEXT).maxLength(200).help("Facultative : affine le prix du marché."), defaults),
                Components.input(Input.of("Surface (m²)", "area", Field.NUMBER), defaults),
                Components.input(Input.of("Coût d'acquisition (€)", "purchase", Field.MONEY).help("Prix + frais de notaire + travaux, selon l'acte."), defaults),
                Components.input(Input.of("Date d'acquisition", "purchase_date", Field.DATE).required(), defaults),
                Components.input(Input.of("Estimation prudente (€)", "estimate", Field.MONEY).help("Ce que vous pensez pouvoir en tirer au minimum."), defaults),
                Components.input(Input.of("Estimation optimiste (€)", "estimate_high", Field.MONEY), defaults),
                Components.input(Input.of("Date de l'estimation", "estimate_date", Field.DATE).required(), defaults),
                Components.input(Input.of("Frais de vente et fiscalité (%)", "sale_fees_pct", Field.NUMBER).help("8 % par défaut ; environ 5 % pour une résidence principale."), defaults)),
                "Ajouter ce bien");
        return Html.concat(explain("Un bien par logement possédé : résidence principale, logement loué, résidence secondaire. Les montants viennent de l'acte "
                        + "d'achat ; une estimation suffit pour la valeur (agence, sites d'estimation), Patria l'actualise ensuite avec les prix du département."),
                Components.card("Biens", properties.size(), Html.EMPTY, Html.concat(list, add)));
    }

    static Html loans(WizardController.Context context) {
        List<Property> properties = context.snapshot().properties();
        if (properties.isEmpty()) {
            return Html.concat(explain("Les prêts se rattachent à un bien."),
                    Components.card("Prêts", null, Html.EMPTY, Html.raw("<p class=\"empty\">Aucun bien : ajoutez-en un à l'étape Biens immobiliers, ou passez cette étape.</p>")));
        }
        Html cards = properties.stream().map(property -> {
            List<fr.patria.loan.Loan> loans = context.snapshot().loans().stream().filter(loan -> loan.propertyId() == property.id()).toList();
            Html list = loans.isEmpty() ? Html.raw("<p class=\"muted\">Aucun prêt importé pour ce bien.</p>")
                    : Html.of("<ul class=\"done-list\">%s</ul>", loans.stream().map(loan -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span><a href=\"/pret/%s\">%s</a></li>",
                    loan.id(), loan.name())).collect(Html.joining()));
            Html add = form(context, "/assistant/prets/" + property.id(), true, Html.concat(Components.fields(2,
                    Components.input(Input.of("Nom du prêt", "name", Field.TEXT).required().maxLength(100).placeholder("Prêt principal, prêt à taux zéro…"), Map.of()),
                    Html.raw("<label>Fichier CSV (facultatif)<input name=\"csv\" type=\"file\" accept=\".csv,text/csv\"></label>")), Components.localAiInput("echeancier"),
                    Html.raw("<label>Ou collez la réponse de l'IA<textarea name=\"texte\" rows=\"10\" placeholder=\"Collez ici les lignes de l'échéancier\"></textarea></label>")),
                    "Importer ce prêt");
            return Html.of("<section class=\"card section\" id=\"bien-%s\"><div class=\"section-title\"><h2>%s</h2></div>%s%s%s</section>",
                    property.id(), property.name(), list, Components.llmPrompt("prompt-pret-" + property.id(), CsvScheduleParser.llmPrompt()), add);
        }).collect(Html.joining());
        return Html.concat(explain("Un prêt = son échéancier : un fichier CSV préparé à la main, ou la réponse d'une IA (copiez le prompt avec le tableau "
                + "d'amortissement, puis collez sa réponse). Vérifiez ensuite le prêt importé. Un prêt à taux zéro ou un "
                + "prêt complémentaire s'importe comme un prêt à part."), cards);
    }

    static Html charges(WizardController.Context context, Map<String, String> values) {
        List<Property> properties = context.snapshot().properties();
        if (properties.isEmpty()) {
            return Html.concat(explain("Les charges et loyers se rattachent à un bien."),
                    Components.card("Charges", null, Html.EMPTY, Html.raw("<p class=\"empty\">Aucun bien : ajoutez-en un à l'étape Biens immobiliers, ou passez cette étape.</p>")));
        }
        Html cards = properties.stream().map(property -> {
            Html list = Html.of("<ul class=\"done-list\">%s%s</ul>",
                    context.snapshot().charges().stream().filter(charge -> charge.propertyId() == property.id())
                            .map(charge -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s %s · %s</li>", charge.kind().label(), charge.year(), euroWhole(charge.amount())))
                            .collect(Html.joining()),
                    context.snapshot().rents().stream().filter(rent -> rent.propertyId() == property.id())
                            .map(rent -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s depuis le %s · %s/mois</li>", rent.kind().label(), Format.date(rent.start()),
                                    euroWhole(rent.monthly()))).collect(Html.joining()));
            Html charge = form(context, "/assistant/charges/" + property.id(), false, Components.fields(3,
                    Components.select("Charge", "kind", Arrays.stream(ExpenseKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(), ExpenseKind.PROPERTY_TAX.name()),
                    Components.input(Input.of("Année", "year", Field.NUMBER).required(), Map.of("year", String.valueOf(context.today().getYear()))),
                    Components.input(Input.of("Montant annuel (€)", "amount", Field.MONEY).required(), Map.of())), "Ajouter cette charge");
            Html occupancy = form(context, "/assistant/occupation/" + property.id(), false, Components.fields(3,
                    Components.select("Occupation", "kind", Arrays.stream(Occupancy.values()).map(kind -> new Option(kind.name(), kind.label())).toList(), Occupancy.RENTAL.name()),
                    Components.input(Input.of("Loyer mensuel (€)", "monthly", Field.MONEY).required().help("Loué : loyer hors charges perçu. Résidence principale : loyer d'un logement équivalent."), Map.of()),
                    Components.input(Input.of("Depuis le", "start", Field.DATE).required(), Map.of("start", property.purchaseDate().toString()))), "Ajouter cette occupation");
            return Html.of("<section class=\"card section\" id=\"bien-%s\"><div class=\"section-title\"><h2>%s</h2></div>%s<h3 class=\"wizard-group\">Charges</h3>%s"
                    + "<h3 class=\"wizard-group\">Occupation</h3>%s</section>", property.id(), property.name(), list, charge, occupancy);
        }).collect(Html.joining());
        return Html.concat(explain("Pour chaque bien : la dernière taxe foncière (un seul avis suffit, les autres années sont estimées), les charges de "
                + "copropriété et l'assurance (montant annuel). Puis son occupation : loué (loyer perçu, qui entre dans le budget) ou résidence principale "
                + "(loyer économisé, pour calculer l'enrichissement réel). La taxe foncière peut être mensualisée depuis Gérer → Biens et prêts → le bien → Charges."), cards);
    }

    static Html shares(WizardController.Context context) {
        List<Person> people = context.snapshot().people();
        if (people.size() < 2 || context.snapshot().properties().isEmpty()) {
            return Html.concat(explain("La répartition dit quelle part de chaque bien et de chaque prêt revient à chaque membre."),
                    Components.card("Répartition", null, Html.EMPTY, Html.raw("<p class=\"empty\">Rien à répartir : il faut au moins deux membres et un bien. Passez cette étape.</p>")));
        }
        Html rows = context.snapshot().properties().stream().map(property -> Html.of("<li><b>%s</b> : %s</li>", property.name(),
                Shares.describe(context.shares().getOrDefault(new Shares.Key(Shares.Subject.PROPERTY, property.id()), Map.of()), people))).collect(Html.joining());
        return Html.concat(explain("Par défaut, chaque bien et chaque prêt est à parts égales entre les personnes (le compte joint n'y prend part que si on lui "
                        + "donne un pourcentage). Si un bien appartient surtout à l'un, ou si le compte joint paie la taxe foncière, ajustez-le."),
                Components.card("Répartition actuelle", null, Components.secondaryButton("/administration/repartition", "Ajuster la répartition"),
                        Html.of("<ul class=\"plain-list\">%s</ul><p class=\"muted\">La page Répartition s'ouvre dans Gérer ; revenez ensuite ici pour terminer l'étape.</p>", rows)));
    }
}
