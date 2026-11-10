// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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

/** Administration du foyer : personnes et revenus. */
public final class HouseholdPages {
    /** Frequences proposees pour un revenu. */
    static final List<Frequency> INCOME_FREQUENCIES = List.of(Frequency.MONTHLY, Frequency.ANNUAL, Frequency.ONCE);

    private HouseholdPages() {
    }

    static Map<String, String> values(Income income) {
        Map<String, String> values = new HashMap<>();
        values.put("person", String.valueOf(income.personId()));
        values.put("label", income.label());
        values.put("kind", income.kind().name());
        values.put("amount", Format.plain(income.amount()));
        values.put("frequency", income.frequency().name());
        values.put("start", income.start().toString());
        values.put("end", income.end() == null ? "" : income.end().toString());
        return values;
    }

    static Html incomeFields(List<Person> people, Map<String, String> values) {
        return Html.concat(Components.fields(3,
                        Components.select("Personne", "person", people.stream().map(person -> new Option(String.valueOf(person.id()), person.name())).toList(),
                                values.getOrDefault("person", people.isEmpty() ? "" : String.valueOf(people.getFirst().id()))),
                        Components.select("Type", "kind", Arrays.stream(IncomeKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                                values.getOrDefault("kind", IncomeKind.SALARY.name())),
                        Components.input(Input.of("Libellé", "label", Field.TEXT).required().maxLength(100).placeholder("Salaire"), values),
                        Components.input(Input.of("Montant net avant impôt (€)", "amount", Field.MONEY).required(), values),
                        Components.select("Fréquence", "frequency", INCOME_FREQUENCIES.stream().map(f -> new Option(f.name(), f.label())).toList(),
                                values.getOrDefault("frequency", Frequency.MONTHLY.name())),
                        Components.input(Input.of("Premier versement", "start", Field.DATE).required().help("Annuel : revient chaque année ce mois-là."), values),
                        Components.input(Input.of("Fin", "end", Field.DATE).help("Vide si en cours. Augmentation : terminer et ajouter le nouveau montant."), values)),
                Html.raw("<p class=\"muted\">Montant net avant impôt sur le revenu, comme sur la fiche de paie (« net à payer avant impôt »). "
                        + "Le prélèvement à la source se saisit dans les dépenses, catégorie « Impôts et taxes ».</p>"));
    }


    /** Revenus visibles par la personne connectee ; un utilisateur ne voit que ceux de ses vues ouvertes. */
    static Html incomes(Visibility visibility, List<Income> allIncomes, Map<String, String> values, Notice notice, String csrf) {
        List<Person> people = visibility.everyone();
        List<Person> formPeople = visibility.people();
        List<Income> incomes = allIncomes.stream().filter(income -> visibility.canSeeAny(income.personId())).toList();
        Html incomeRows = incomes.stream().map(income -> Html.of("<tr>%s%s%s%s%s%s<td class=\"actions\">%s</td></tr>",
                cell(people.stream().filter(person -> person.id() == income.personId()).map(Person::name).findFirst().orElse(Format.DASH)),
                cell(income.label()), cell(income.kind().label()), number(income.amount(), Format.euro(income.amount())),
                cell(income.frequency().label()), cell(period(income)),
                visibility.sees(income.personId()) ? Html.of("<a href=\"/revenu/%s\">Modifier</a>", income.id())
                        : Html.of("<a href=\"/revenu/%s\">Voir</a>", income.id()))).collect(Html.joining());
        Html incomesCard = Components.card("Revenus", incomes.size(), Html.EMPTY, incomes.isEmpty()
                ? Html.raw("<p class=\"empty\">Aucun revenu saisi.</p>")
                : Components.table(List.of("Personne", "Libellé", "Type", "#Montant", "Fréquence", "Période", ""), incomeRows, true, false));
        Html addIncome = formPeople.isEmpty()
                ? Components.card("Ajouter un revenu", null, Html.EMPTY, Html.raw(
                        "<p class=\"empty\">Aucun membre ne vous est accessible : demandez au gestionnaire du foyer de cocher vos membres dans le tableau des droits.</p>"))
                : Components.disclosure("Ajouter un revenu",
                        Components.form("/administration/revenus", csrf, false, incomeFields(formPeople, values), "Ajouter le revenu"), notice != null && notice.error());
        Html importer = formPeople.isEmpty() ? Html.EMPTY : Components.disclosure("Importer (réponse d'une IA ou fichier CSV)", importForm(formPeople, null, csrf), false);
        Html content = Html.concat(Components.heading("Gestion", "Revenus", null, Html.EMPTY), Components.notice(notice), addIncome, importer, incomesCard);
        return Layout.adminSection("Revenus · Patria", csrf, Layout.AdminSection.INCOMES, content);
    }

    /** {@code back} : page d'ou l'on vient (le budget), {@code null} pour revenir aux revenus. */
    static Html edit(Income income, List<Person> people, Map<String, String> values, Notice notice, String back, boolean readOnly, String csrf) {
        String suffix = back == null ? "" : "?retour=" + java.net.URLEncoder.encode(back, java.nio.charset.StandardCharsets.UTF_8);
        Html content = Html.concat(
                back == null ? Components.backLink("/administration/revenus", "Revenus") : Components.backLink(back, "Budget moyen"),
                Components.heading("Revenu", income.label(), null, Html.EMPTY), Components.notice(notice),
                Components.card("Revenu", null, Html.EMPTY, Components.formOrView(readOnly, "/revenu/" + income.id() + suffix, csrf, incomeFields(people, values), "Enregistrer")),
                readOnly ? Html.EMPTY : Components.dangerZone("Supprimer ce revenu", "Il ne sera plus compté dans le budget.", "/revenu/" + income.id() + "/supprimer" + suffix,
                        "Supprimer le revenu « " + income.label() + " » ?", "Supprimer le revenu", csrf));
        return Layout.adminSection("Revenu · " + income.label() + " · Patria", csrf, Layout.AdminSection.INCOMES, content);
    }

    static String period(Income income) {
        if (income.frequency() == Frequency.ONCE) return Format.date(income.start());
        return "depuis " + Format.date(income.start()) + (income.end() == null ? "" : " jusqu'au " + Format.date(income.end()));
    }

    /**
     * Import des revenus : prompt pour les bulletins et attestations, reponse a coller (ou fichier, ou document pour
     * l'IA locale). {@code back} : etape du guide ou revenir apres l'import.
     */
    public static Html importForm(List<Person> people, String back, String csrf) {
        List<String> names = people.stream().filter(person -> !person.isAccount()).map(Person::name).toList();
        return Html.concat(Components.llmPrompt("prompt-revenus" + (back == null ? "" : "-guide"), IncomeCsv.llmPrompt(names)), Html.of("""
                <form method="post" action="/administration/revenus/import" class="stack" enctype="multipart/form-data">%s%s%s
                <div class="form-actions"><button type="submit">Voir l'aperçu</button></div></form>""",
                Components.csrfField(csrf), back == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"retour\" value=\"%s\">", back),
                Components.importInput("Fichier CSV", "csv", "revenus|" + String.join(",", names))));
    }

    /** Apercu de l'import des revenus : une case par revenu valide et nouveau. */
    static Html importPreview(List<IncomeCsv.Row> rows, String content, String back, String csrf) {
        long fresh = rows.stream().filter(IncomeCsv.Row::fresh).count();
        Html body = rows.stream().map(row -> row.valid() ? Html.of("<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td>%s<td>%s</td><td>%s</td></tr>",
                row.fresh() ? Html.of("<input type=\"checkbox\" name=\"importer_%s\" value=\"on\" form=\"revenus-import\" checked aria-label=\"Importer %s\">",
                        row.line(), row.income().label()) : Html.text(String.valueOf(row.line())),
                row.person(), row.income().label(), row.income().kind().label(), number(row.income().amount(), Format.euro(row.income().amount())),
                row.income().frequency().label(), row.duplicate() ? Html.raw("<span class=\"badge\">Déjà saisi</span>") : Html.raw("<span class=\"badge paid\">À importer</span>"))
                : Html.of("<tr class=\"row-error\"><td>%s</td><td colspan=\"6\" class=\"negative\">%s</td></tr>", row.line(), row.error())).collect(Html.joining());
        String encoded = java.util.Base64.getUrlEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String cancel = back == null ? "/administration/revenus" : back;
        Html actions = fresh == 0 ? Html.raw("<p class=\"muted\">Rien de nouveau à importer.</p>") : Html.of("""
                <form id="revenus-import" method="post" action="/administration/revenus/import/valider" class="form-actions">%s<input type="hidden" name="contenu" value="%s">%s\
                <a class="button secondary" href="%s">Annuler</a><button type="submit">Importer la sélection</button></form>""",
                Components.csrfField(csrf), encoded, back == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"retour\" value=\"%s\">", back), cancel);
        Html content2 = Html.concat(Components.backLink(cancel, back == null ? "Revenus" : "Retour au guide"),
                Components.heading("Gestion", "Aperçu de l'import", fresh + " revenu" + (fresh > 1 ? "s" : "") + " à importer. Les lignes en erreur sont ignorées.", Html.EMPTY),
                Components.card("Revenus", rows.size(), Html.EMPTY, Html.concat(Components.table(List.of("Importer", "Personne", "Libellé", "Type", "#Montant", "Fréquence", "État"),
                        body, false, false), actions)));
        return Layout.adminSection("Import des revenus · Patria", csrf, Layout.AdminSection.INCOMES, content2);
    }
}
