package fr.patria.property;

import static fr.patria.ui.Components.Field.DATE;
import static fr.patria.ui.Components.Field.MONEY;
import static fr.patria.ui.Components.Field.NUMBER;
import static fr.patria.ui.Components.Field.TEXT;

import fr.patria.ui.Components;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.Arrays;
import java.util.Map;

/** Formulaire de creation et de modification d'un bien. */
final class PropertyPages {
    private PropertyPages() {
    }

    /** {@code property} vaut {@code null} pour un nouveau bien. */
    static Html form(Property property, Map<String, String> values, Notice notice, String csrf) {
        boolean creating = property == null;
        Html fields = Html.concat(
                section("Informations", Html.concat(
                        Components.fields(2,
                                Components.input(Input.of("Nom du bien", "name", TEXT).required().maxLength(100), values),
                                Components.select("Type", "kind", Arrays.stream(PropertyKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(), values.get("kind")),
                                Components.input(Input.of("Commune", "commune", TEXT).maxLength(100), values),
                                Components.input(Input.of("Surface (m²)", "area", NUMBER), values)),
                        Components.input(Input.of("Adresse", "address", TEXT).maxLength(200), values))),
                section("Acquisition", Components.fields(2,
                        Components.input(Input.of("Coût d'acquisition (€)", "purchase", MONEY).help("Prix, frais de notaire et travaux inclus."), values),
                        Components.input(Input.of("Date d'acquisition", "purchase_date", DATE), values))),
                section("Estimation", Components.fields(2,
                        Components.input(Input.of("Estimation prudente (€)", "estimate", MONEY).help("Vide : le coût d'acquisition est utilisé."), values),
                        Components.input(Input.of("Estimation optimiste (€)", "estimate_high", MONEY).help("Vide : identique à l'estimation prudente."), values),
                        Components.input(Input.of("Date d'estimation", "estimate_date", DATE), values))),
                section("Valeur nette", Html.concat(
                        Components.fields(2,
                                Components.input(Input.of("Frais de vente et fiscalité (%)", "sale_fees_pct", NUMBER).placeholder("8").help("Résidence principale : plus-value exonérée, compter les frais d'agence (environ 5 %). Autre bien : ajouter l'impôt sur la plus-value (19 % + 17,2 %, réduit selon la durée de détention)."), values)),
                        Html.raw("<p class=\"muted\">Valeur nette indicative = valeur retenue − frais de vente et fiscalité − capital restant dû.</p>"))));
        if (creating) {
            Html content = Html.concat(Components.backLink("/administration", "Administration"),
                    Components.heading("Administration", "Nouveau bien", null, Html.EMPTY), Components.notice(notice),
                    Components.form("/bien", csrf, false, fields, "Créer le bien"));
            return Layout.page("Nouveau bien · Patria", Layout.Section.ADMINISTRATION, csrf, content);
        }
        Html content = Html.concat(
                Components.heading("Bien", "Modifier", property.name(), Html.EMPTY), Components.notice(notice),
                Components.form("/administration/bien/" + property.id(), csrf, false, fields, "Enregistrer"),
                Components.dangerZone("Supprimer ce bien", "Supprime définitivement le bien, ses prêts, leurs échéanciers et ses apports.",
                        "/administration/bien/" + property.id() + "/supprimer", "Supprimer définitivement « " + property.name() + " » et toutes ses données ?",
                        "Supprimer le bien", csrf));
        return Layout.adminPage("Modifier · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.INFORMATION), content);
    }

    private static Html section(String title, Html body) {
        return Html.of("<section class=\"card section\"><h2>%s</h2>%s</section>", title, body);
    }
}
