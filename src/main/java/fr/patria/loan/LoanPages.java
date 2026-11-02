package fr.patria.loan;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.sortedCell;
import static fr.patria.ui.Components.tile;
import static fr.patria.ui.Format.date;
import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.percent;

import fr.patria.property.Property;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Pages d'un pret : detail, gestion, creation. */
final class LoanPages {
    private LoanPages() {
    }

    static Html detail(Loan loan, Property property, List<ScheduleLine> lines, LoanMath.Status status, LocalDate today, Notice notice, String csrf) {
        Html rows = lines.stream().map(line -> Html.of("<tr%s>%s%s%s%s%s%s%s%s%s</tr>",
                Html.raw(line == status.nextInstallment() ? " class=\"highlight\"" : ""),
                sortedCell(line.date(), date(line.date())), cell(line.type().label()), cell(Components.paidBadge(LoanMath.isPast(line, today))),
                number(line.amount(), euro(line.amount())), number(line.principalRepaid(), euro(line.principalRepaid())),
                number(line.interest(), euro(line.interest())), number(line.fees(), euro(line.fees())),
                number(line.remainingPrincipal(), euro(line.remainingPrincipal())), number(line.annualRate(), percent(line.annualRate()))))
                .collect(Html.joining());
        Html table = lines.isEmpty() ? Html.raw("<p class=\"empty\">Aucune échéance importée.</p>")
                : Components.table(List.of("Date", "Type", "Statut", "#Montant", "#Capital", "#Intérêts", "#Frais", "#Restant dû", "#Taux"), rows, true, true);
        ScheduleLine next = status.nextInstallment();
        Html content = Html.concat(
                Components.backLink("/bien/" + property.id(), property.name()),
                Components.heading("Prêt", loan.name(), "Finance « " + property.name() + " »", Components.secondaryButton("/pret/" + loan.id() + "/gerer", "Gérer le prêt")),
                Components.notice(notice),
                Components.metrics(3,
                        tile("Capital emprunté", "Total des déblocages de l'échéancier.", euro(status.borrowed()), null),
                        tile("Capital restant dû", "Capital restant dû à aujourd'hui. Avant la première échéance, c'est le capital emprunté.", euro(status.remaining()), status.progressPercent() + " % remboursé"),
                        tile("Capital remboursé", "Part de capital des échéances déjà passées.", euro(status.repaid()), null),
                        tile("Taux actuel", "Taux annuel de la dernière échéance passée.", percent(status.currentRate()), null),
                        tile("Prochaine échéance", null, next == null ? Format.DASH : euro(next.amount()), next == null ? "Aucune échéance à venir" : date(next.date())),
                        tile("Durée restante", "Durée jusqu'à la dernière échéance connue.", Format.duration(status.remainingMonths()),
                                status.lastDate() == null ? null : "Fin le " + date(status.lastDate()))),
                Html.of("<section class=\"card section\"><div class=\"section-title\"><h2>Progression</h2><span class=\"muted\">%s %% du capital emprunté</span></div>%s</section>".replace("%%", "%"),
                        status.progressPercent(), Components.progress(status.progressPercent())),
                Components.card("Échéancier", lines.size(), Html.EMPTY, table));
        return Layout.page("Prêt · " + loan.name() + " · Patria", Layout.Section.OVERVIEW, csrf, content);
    }

    static Html manage(Loan loan, Property property, Notice notice, String csrf) {
        Html rename = Components.card("Nom du prêt", null, Html.EMPTY, Components.form("/pret/" + loan.id() + "/renommer", csrf, false,
                Components.input(Input.of("Nom", "name", Field.TEXT).required().maxLength(100), Map.of("name", loan.name())), "Renommer"));
        Html schedule = Components.card("Remplacer l'échéancier", null, Html.EMPTY, Html.concat(
                Html.raw("<p class=\"muted\">Le nouvel import remplace toutes les lignes existantes.</p>"),
                Components.form("/pret/" + loan.id() + "/echeancier", csrf, true, Components.fileInput("Fichier CSV", "csv"), "Importer"),
                csvHelp()));
        Html content = Html.concat(
                Components.backLink("/pret/" + loan.id(), loan.name()),
                Components.heading("Prêt", "Gérer le prêt", loan.name() + " · " + property.name(), Html.EMPTY),
                Components.notice(notice), rename, schedule,
                Components.dangerZone("Supprimer ce prêt", "Supprime le prêt et son échéancier.", "/pret/" + loan.id() + "/supprimer",
                        "Supprimer le prêt « " + loan.name() + " » ?", "Supprimer le prêt", csrf));
        return Layout.page("Gérer · " + loan.name() + " · Patria", Layout.Section.OVERVIEW, csrf, content);
    }

    static Html create(Property property, Map<String, String> values, Notice notice, String csrf) {
        Html form = Components.form("/bien/" + property.id() + "/prets", csrf, true, Components.fields(2,
                Components.input(Input.of("Nom du prêt", "name", Field.TEXT).required().maxLength(100).placeholder("Prêt principal"), values),
                Components.fileInput("Échéancier CSV", "csv")), "Importer le prêt");
        Html content = Html.concat(
                Components.backLink("/administration/bien/" + property.id() + "/financements", "Prêts et apports"),
                Components.heading("Bien", "Nouveau prêt", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Prêt", null, Html.EMPTY, Html.concat(form, csvHelp())));
        return Layout.page("Nouveau prêt · " + property.name() + " · Patria", Layout.Section.ADMINISTRATION, csrf, content);
    }

    /** Aide sur le format CSV, repliee par defaut. */
    static Html csvHelp() {
        return Html.of("""
                <details class="help-block"><summary>Format du fichier CSV</summary>
                <p class="muted">Fichier UTF-8 de 1 Mio maximum, séparateur virgule ou point-virgule, sans guillemets. Les nombres acceptent la virgule ou le point décimal.</p>
                <pre><code>%s
                2026-01-05,ECHEANCE,850.00,500.00,330.00,20.00,14500.00,3.20</code></pre>
                <p class="muted"><code>date</code> (AAAA-MM-JJ) et <code>type</code> sont obligatoires ; chaque ligne contient au moins un montant.
                Types : <code>DEBLOCAGE</code>, <code>ECHEANCE</code>, <code>REMBOURSEMENT_ANTICIPE</code>, <code>CONSOLIDATION</code>, <code>AJUSTEMENT</code>.</p></details>""",
                CsvScheduleParser.HEADER);
    }
}
