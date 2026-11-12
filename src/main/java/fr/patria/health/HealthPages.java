// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.PdfReport;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Pages du bilan de sante. HTML uniquement. */
final class HealthPages {
    private HealthPages() {
    }

    /** Ancre de la liste de chaque niveau : une tuile y mene et l'ouvre. */
    static String anchor(Check.Level level) {
        return switch (level) {
            case BAD -> "a-corriger";
            case WARN -> "a-surveiller";
            case INFO -> "bon-a-savoir";
            case GOOD -> "points-rassurants";
        };
    }

    static Html page(String owner, List<Check> checks, List<Check> ignored, boolean estate, Html scope, String csrf) {
        return page(owner, checks, ignored, estate, scope, Html.EMPTY, csrf);
    }

    /** {@code evolution} : section « Votre evolution », en bas du Bilan. */
    static Html page(String owner, List<Check> checks, List<Check> ignored, boolean estate, Html scope, Html evolution, String csrf) {
        Html tiles = Components.metrics(4, Arrays.stream(Check.Level.values()).map(level -> {
            long count = checks.stream().filter(check -> check.level() == level).count();
            // Toute la tuile mene a sa liste (et la deplie) ; sans point, rien a ouvrir.
            return count == 0
                    ? Html.of("<article class=\"metric health-tile %s\"><span class=\"metric-label\">%s</span><strong>0</strong><small>point</small></article>", css(level), level.label())
                    : Html.of("<a class=\"metric health-tile %s\" href=\"#%s\" data-open-target><span class=\"metric-label\">%s</span><strong>%s</strong><small>%s</small></a>",
                    css(level), anchor(level), level.label(), count, count > 1 ? "sujets" : "sujet");
        }).toArray(Html[]::new));
        Html domains = Arrays.stream(Check.Domain.values()).map(domain -> {
            List<Check> own = checks.stream().filter(check -> check.domain() == domain && check.level() == Check.Level.INFO).toList();
            if (own.isEmpty()) {
                String empty = domain == Check.Domain.PROPERTIES && !estate ? "L'immobilier n'est pas ouvert pour votre compte." : "Rien à signaler pour l'instant.";
                if (ignored.stream().anyMatch(check -> check.domain() == domain)) empty = "Les points de cette rubrique sont dans « Points ignorés » ci-dessous.";
                if (checks.stream().anyMatch(check -> check.domain() == domain)) return Html.EMPTY;
                return Components.card(domain.label(), null, Html.EMPTY, Html.of("<p class=\"muted\">%s</p>", empty));
            }
            return Html.of("<section class=\"card section\" data-level-info><div class=\"section-title\"><h2>%s</h2><span class=\"muted\">%s</span></div><ul class=\"health-list\">%s</ul></section>",
                    domain.label(), domain.summary(), own.stream().map(check -> item(check, false, csrf)).collect(Html.joining()));
        }).collect(Html.joining());
        Html content = Html.concat(
                Components.heading(owner, "Bilan de santé", "Vos finances, vos biens et vos impôts passés en revue, à partir de ce qui est saisi.",
                        Components.pdfButton("/bilan.pdf")),
                scope, tiles,
                Html.of("<div id=\"%s\">%s</div>", anchor(Check.Level.BAD), priority("À traiter en priorité", Check.Level.BAD, checks, csrf)),
                Html.of("<div id=\"%s\">%s</div>", anchor(Check.Level.WARN), priority("À surveiller", Check.Level.WARN, checks, csrf)),
                Html.of("<div id=\"%s\">%s</div>", anchor(Check.Level.INFO), domains),
                checks.stream().noneMatch(check -> check.level() == Check.Level.GOOD) ? Html.EMPTY : Html.of("<div id=\"%s\">%s</div>", anchor(Check.Level.GOOD),
                        Components.disclosure("Points rassurants", Html.of("<ul class=\"health-list\">%s</ul>",
                        checks.stream().filter(check -> check.level() == Check.Level.GOOD).map(check -> item(check, false, csrf)).collect(Html.joining())), false)),
                Html.of("""
                        <section class="card section" id="ignores"><details%s><summary>Points ignorés (%s)</summary>
                        <p class="muted">Ces choix ne concernent que votre compte et ce foyer. Les points ignorés ne comptent plus dans le résumé ni les compteurs.
                        Une alerte dont la gravité augmente réapparaît. Les points ignorés restent mentionnés dans le PDF.</p>%s</details></section>""",
                        Html.raw(ignored.isEmpty() ? "" : " open"), ignored.size(), ignored.isEmpty()
                                ? Html.raw("<p class=\"muted\">Aucun point ignoré actuellement.</p>")
                                : Html.of("<ul class=\"health-list\">%s</ul>", ignored.stream().map(check -> item(check, true, csrf)).collect(Html.joining()))),
                Html.raw("<p class=\"source\">Repères indicatifs, calculés sur les 12 prochains mois et les données saisies : ils ne remplacent pas un conseiller. "
                        + "Plus les données sont à jour (revue annuelle), plus le bilan est juste.</p>"), evolution);
        return Layout.page("Bilan de santé · Patria", Layout.Section.HEALTH, csrf, content);
    }

    /**
     * Comptes pris en compte dans le bilan, repliable : cases des membres visibles, cochees selon le perimetre actuel
     * ({@code configured} : choisi par l'utilisateur, sinon par defaut).
     */
    static Html scope(List<fr.patria.household.Person> people, java.util.Set<Long> chosen, boolean configured, String action, String csrf) {
        if (people.isEmpty()) return Html.EMPTY;
        String names = chosen.isEmpty() ? "la vue du budget" : String.join(", ", people.stream().filter(person -> chosen.contains(person.id())).map(fr.patria.household.Person::name).toList());
        Html boxes = people.stream().map(person -> Html.of("<label class=\"check\"><input type=\"checkbox\" name=\"membre_%s\" value=\"on\"%s>%s%s</label>",
                person.id(), Html.raw(chosen.isEmpty() || chosen.contains(person.id()) ? " checked" : ""), person.name(), person.isAccount() ? " (compte joint)" : "")).collect(Html.joining());
        return Html.of("""
                <details class="card section health-scope"><summary><b>Comptes pris en compte :</b> %s%s</summary>
                <form method="post" action="%s" class="stack">%s<p class="muted">Par défaut : le membre que vous représentez et les comptes joints que vous voyez.
                Ajoutez d'autres comptes pour un bilan plus large.</p><div class="chips-line">%s</div>
                <div class="form-actions"><button type="submit">Enregistrer</button></div></form></details>""",
                names, configured ? "" : " (par défaut)", action, Components.csrfField(csrf), boxes);
    }

    private static Html priority(String title, Check.Level level, List<Check> checks, String csrf) {
        List<Check> own = checks.stream().filter(check -> check.level() == level).toList();
        return own.isEmpty() ? Html.EMPTY : Components.card(title, own.size(), Html.EMPTY,
                Html.of("<ul class=\"health-list\">%s</ul>", own.stream().map(check -> item(check, false, csrf)).collect(Html.joining())));
    }

    private static Html item(Check check, boolean ignored, String csrf) {
        Html action = !check.canIgnore() ? Html.EMPTY : Html.of("""
                <form method="post" action="/bilan/%s" class="inline-form">%s<input type="hidden" name="check" value="%s">                <button class="%s small" type="submit">%s</button></form>""",
                ignored ? "retablir" : "ignorer", Components.csrfField(csrf), check.token(), ignored ? "secondary" : "quiet", ignored ? "Rétablir" : "Ignorer pour moi");
        Html link = check.link() == null ? Html.EMPTY : Html.of("<a class=\"button secondary small\" href=\"%s\">%s</a>", check.link(), check.linkLabel());
        Html actions = link.isEmpty() && action.isEmpty() ? Html.EMPTY : Html.of("<div class=\"health-actions\">%s%s</div>", link, action);
        return Html.of("<li class=\"health-item %s\"><span class=\"health-mark\" aria-label=\"%s\">%s</span><div><b>%s</b><p>%s</p>%s</div></li>",
                css(check.level()), check.level().label(), check.level().mark(), check.title(), check.detail(), actions);
    }

    private static String css(Check.Level level) {
        return switch (level) {
            case BAD -> "level-bad";
            case WARN -> "level-warn";
            case INFO -> "level-info";
            case GOOD -> "level-good";
        };
    }

    /** Encart du Previsionnel. */
    static Html summary(long bad, long warn) {
        String text = (bad > 0 ? Format.plural(bad, "sujet à corriger", "sujets à corriger") : "")
                + (bad > 0 && warn > 0 ? ", " : "") + (warn > 0 ? Format.plural(warn, "sujet à surveiller", "sujets à surveiller") : "");
        return Html.of("<section class=\"notice %s health-summary\"><div><b>Bilan de santé</b> : %s.</div><a class=\"button secondary\" href=\"/bilan\">Voir le bilan</a></section>",
                bad > 0 ? "error" : "warning", text);
    }

    static byte[] pdf(String owner, List<Check> checks, List<Check> ignored, LocalDate today) {
        PdfReport report = PdfReport.create("Bilan de santé", owner, today).metrics(Arrays.stream(Check.Level.values())
                .map(level -> PdfReport.Metric.of(level.label(), String.valueOf(checks.stream().filter(check -> check.level() == level).count()), "sujets")).toList());
        for (Check.Domain domain : Check.Domain.values()) {
            List<Check> own = checks.stream().filter(check -> check.domain() == domain).sorted(Comparator.comparing(Check::level)).toList();
            if (own.isEmpty()) continue;
            report.section(domain.label()).table(List.of("État", "Point", "Détail"), own.stream()
                    .map(check -> PdfReport.row(check.level().label(), check.title(), check.detail())).toList(), new float[]{1, 2, 4.5f}, false);
        }
        if (!ignored.isEmpty()) {
            report.section("Points ignorés par votre compte (hors compteurs)").table(List.of("État initial", "Point", "Détail"), ignored.stream()
                    .map(check -> PdfReport.row(check.level().label(), check.title(), check.detail())).toList(), new float[]{1, 2, 4.5f}, false);
        }
        report.note("Repères indicatifs calculés à partir des données saisies dans Patria ; ils ne remplacent pas un conseiller.");
        return report.toBytes();
    }
}
