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
import java.util.List;
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
            Html content = Html.concat(Components.backLink("/administration/biens", "Biens et prêts"),
                    Components.heading("Administration", "Nouveau bien", null, Html.EMPTY), Components.notice(notice),
                    Components.form("/bien", csrf, false, fields, "Créer le bien"));
            return Layout.page("Nouveau bien · Patria", Layout.Section.ADMINISTRATION, csrf, content);
        }
        Html content = Html.concat(
                Components.heading("Bien", "Modifier", property.name(), Html.EMPTY), Components.notice(notice),
                Components.form("/administration/bien/" + property.id(), csrf, false, fields, "Enregistrer"),
                moveCard(property, csrf),
                Components.dangerZone("Supprimer ce bien", "Supprime définitivement le bien, ses prêts, leurs échéanciers et ses apports.",
                        "/administration/bien/" + property.id() + "/supprimer", "Supprimer définitivement « " + property.name() + " » et toutes ses données ?",
                        "Supprimer le bien", csrf));
        return Layout.adminPage("Modifier · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.INFORMATION), content);
    }

    /** Deplacer le bien dans un autre foyer dont la personne connectee est aussi gestionnaire. */
    private static Html moveCard(Property property, String csrf) {
        Layout.Chrome chrome = Layout.CHROME.get();
        if (chrome == null || !chrome.manages()) return Html.EMPTY;
        List<Components.Option> others = chrome.households().stream().filter(choice -> choice.id() != chrome.household() && choice.managed())
                .map(choice -> new Components.Option(String.valueOf(choice.id()), choice.name())).toList();
        if (others.isEmpty()) return Html.EMPTY;
        String current = chrome.households().stream().filter(choice -> choice.id() == chrome.household()).map(Layout.Choice::name).findFirst().orElse("");
        return Components.card("Foyer du bien", null, Html.EMPTY, Html.concat(Html.of("""
                        <p class="muted">Ce bien est dans le foyer « %s ». Le déplacer l'emporte avec ses prêts, échéanciers, apports, charges, périodes
                        d'occupation et prix du marché : il disparaît de ce foyer. Sa répartition entre les membres repart à « parts égales » et le payeur de
                        la taxe foncière à « comme le bien », car les membres ne sont pas les mêmes.</p>""", current),
                Html.of("<form method=\"post\" action=\"/administration/bien/%s/foyer\" class=\"inline-form row-form\" data-confirm=\"%s\">%s%s<button type=\"submit\">Déplacer</button></form>",
                        property.id(), "Déplacer « " + property.name() + " » dans l'autre foyer ?", Components.csrfField(csrf),
                        Components.select("Déplacer vers", "foyer", others, others.getFirst().value()))));
    }

    private static Html section(String title, Html body) {
        return Html.of("<section class=\"card section\"><h2>%s</h2>%s</section>", title, body);
    }
}
