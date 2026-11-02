package fr.patria.contribution;

import fr.patria.property.Property;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.HashMap;
import java.util.Map;

/** Formulaire d'ajout et de modification d'un apport. */
final class ContributionPages {
    private ContributionPages() {
    }

    static Map<String, String> values(Contribution contribution) {
        Map<String, String> values = new HashMap<>();
        values.put("name", contribution.name());
        values.put("date", contribution.date().toString());
        values.put("amount", Format.plain(contribution.amount()));
        return values;
    }

    /** {@code contribution} vaut {@code null} pour un nouvel apport. */
    static Html form(Property property, Contribution contribution, Map<String, String> values, Notice notice, String csrf) {
        boolean creating = contribution == null;
        Html fields = Components.fields(3,
                Components.input(Input.of("Libellé", "name", Field.TEXT).required().maxLength(100).placeholder("Épargne personnelle"), values),
                Components.input(Input.of("Date", "date", Field.DATE).required(), values),
                Components.input(Input.of("Montant (€)", "amount", Field.MONEY).required(), values));
        String action = creating ? "/bien/" + property.id() + "/apports" : "/apport/" + contribution.id();
        Html content = Html.concat(
                Components.backLink("/administration/bien/" + property.id() + "/financements", "Prêts et apports"),
                Components.heading("Apport", creating ? "Nouvel apport" : contribution.name(), property.name(), Html.EMPTY),
                Components.notice(notice),
                Components.card("Apport", null, Html.EMPTY, Components.form(action, csrf, false, fields, creating ? "Ajouter l'apport" : "Enregistrer")),
                creating ? Html.EMPTY : Components.dangerZone("Supprimer cet apport", "L'apport sera retiré du bien.", "/apport/" + contribution.id() + "/supprimer",
                        "Supprimer l'apport « " + contribution.name() + " » ?", "Supprimer l'apport", csrf));
        return Layout.page((creating ? "Nouvel apport" : "Apport · " + contribution.name()) + " · Patria", Layout.Section.ADMINISTRATION, csrf, content);
    }
}
