package fr.patria.household;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;

import fr.patria.common.Frequency;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Administration des virements entre membres du foyer. */
final class TransferPages {
    private static final String EXPLANATION = "Une dépense pour qui envoie, une recette pour qui reçoit : chacun le voit dans son budget. "
            + "Sans effet sur le budget du foyer ni sur le taux d'endettement.";

    private TransferPages() {
    }

    static Map<String, String> values(Transfer transfer) {
        Map<String, String> values = new HashMap<>();
        values.put("from", String.valueOf(transfer.fromId()));
        values.put("to", String.valueOf(transfer.toId()));
        values.put("label", transfer.label());
        values.put("amount", Format.plain(transfer.amount()));
        values.put("frequency", transfer.frequency().name());
        values.put("start", transfer.start().toString());
        values.put("end", transfer.end() == null ? "" : transfer.end().toString());
        return values;
    }

    /** Virements visibles par la personne connectee ; ceux qui concernent un membre non ouvert sont en lecture seule. */
    static Html page(Visibility visibility, List<Transfer> allTransfers, Map<String, String> values, Notice notice, String csrf) {
        List<Person> people = visibility.everyone();
        List<Person> formPeople = visibility.people();
        List<Transfer> transfers = allTransfers.stream().filter(transfer -> visibility.canSeeAny(transfer.fromId(), transfer.toId())).toList();
        Html heading = Components.heading("Administration", "Virements", EXPLANATION, Html.EMPTY);
        if (people.size() < 2) {
            return Layout.adminSection("Virements · Patria", csrf, Layout.AdminSection.TRANSFERS, Html.concat(heading, Components.notice(notice),
                    Components.emptyState("Un seul membre dans le foyer", "Un virement va d'un membre à un autre : ajoutez un compte joint ou une deuxième personne.",
                            Components.button("/administration/membres", "Membres"))));
        }
        Html rows = transfers.stream().map(transfer -> Html.of("<tr>%s%s%s%s%s<td class=\"actions\">%s</td></tr>",
                cell(name(people, transfer.fromId()) + " → " + name(people, transfer.toId())), cell(transfer.label().isBlank() ? Format.DASH : transfer.label()),
                number(transfer.amount(), Format.euro(transfer.amount())), cell(transfer.frequency().label()), cell(period(transfer)),
                visibility.canEditAll(transfer.fromId(), transfer.toId()) ? Html.of("<a href=\"/virement/%s\">Modifier</a>", transfer.id())
                        : Html.of("<a href=\"/virement/%s\">Voir</a>", transfer.id()))).collect(Html.joining());
        Html list = transfers.isEmpty() ? Html.raw("<p class=\"empty\">Aucun virement.</p>")
                : Components.table(List.of("Virement", "Libellé", "#Montant", "Fréquence", "Période", ""), rows, true, false);
        Html content = Html.concat(heading, Components.notice(notice),
                formPeople.size() < 2 ? Html.EMPTY
                        : Components.card("Nouveau virement", null, Html.EMPTY, Components.form("/administration/virements", csrf, false, fields(formPeople, values), "Ajouter le virement")),
                Components.card("Virements", transfers.size(), Html.EMPTY, list));
        return Layout.adminSection("Virements · Patria", csrf, Layout.AdminSection.TRANSFERS, content);
    }

    static Html edit(Transfer transfer, List<Person> people, List<Person> formPeople, Map<String, String> values, Notice notice, String back, boolean readOnly,
                     String csrf) {
        String suffix = back == null ? "" : "?retour=" + java.net.URLEncoder.encode(back, java.nio.charset.StandardCharsets.UTF_8);
        String title = name(people, transfer.fromId()) + " → " + name(people, transfer.toId());
        Html content = Html.concat(
                back == null ? Components.backLink("/administration/virements", "Virements") : Components.backLink(back, "Budget moyen"),
                Components.heading("Virement", title, transfer.label().isBlank() ? null : transfer.label(), Html.EMPTY), Components.notice(notice),
                Components.card("Virement", null, Html.EMPTY, Components.formOrView(readOnly, "/virement/" + transfer.id() + suffix, csrf,
                        fields(readOnly ? people : formPeople, values), "Enregistrer")),
                readOnly ? Html.EMPTY : Components.dangerZone("Supprimer ce virement", "Il disparaîtra du budget des deux membres.", "/virement/" + transfer.id() + "/supprimer" + suffix,
                        "Supprimer le virement « " + title + " » ?", "Supprimer le virement", csrf));
        return Layout.adminSection("Virement · " + title + " · Patria", csrf, Layout.AdminSection.TRANSFERS, content);
    }

    private static Html fields(List<Person> people, Map<String, String> values) {
        List<Option> members = people.stream().map(person -> new Option(String.valueOf(person.id()), person.name())).toList();
        return Components.fields(3,
                        Components.select("De", "from", members, values.getOrDefault("from", "")),
                        Components.select("Vers", "to", members, values.getOrDefault("to", "")),
                        Components.input(Input.of("Libellé", "label", Field.TEXT).maxLength(100).placeholder("Virement").help("Facultatif."), values),
                        Components.input(Input.of("Montant (€)", "amount", Field.MONEY).required(), values),
                        Components.select("Fréquence", "frequency", Arrays.stream(Frequency.values()).map(f -> new Option(f.name(), f.label())).toList(),
                                values.getOrDefault("frequency", Frequency.MONTHLY.name())),
                        Components.input(Input.of("Premier virement", "start", Field.DATE).required().help("Ponctuel : la date du virement."), values),
                        Components.input(Input.of("Dernier virement", "end", Field.DATE).help("Vide si sans fin. Ignoré pour un virement ponctuel."), values));
    }

    private static String name(List<Person> people, long id) {
        return people.stream().filter(person -> person.id() == id).map(Person::name).findFirst().orElse("?");
    }

    static String period(Transfer transfer) {
        if (transfer.frequency() == Frequency.ONCE) return "le " + Format.date(transfer.start());
        return "depuis " + Format.date(transfer.start()) + (transfer.end() == null ? "" : " jusqu'au " + Format.date(transfer.end()));
    }
}
