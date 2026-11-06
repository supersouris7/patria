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

    static Html page(String owner, List<Check> checks, List<Check> ignored, boolean estate, String csrf) {
        Html tiles = Components.metrics(4, Arrays.stream(Check.Level.values()).map(level -> Html.of(
                "<article class=\"metric health-tile %s\"><span class=\"metric-label\">%s</span><strong>%s</strong><small>%s</small></article>",
                css(level), level.label(), checks.stream().filter(check -> check.level() == level).count(), "point" + (checks.stream().filter(check -> check.level() == level).count() > 1 ? "s" : "")))
                .toArray(Html[]::new));
        Html domains = Arrays.stream(Check.Domain.values()).map(domain -> {
            List<Check> own = checks.stream().filter(check -> check.domain() == domain && check.level() == Check.Level.INFO).toList();
            if (own.isEmpty()) {
                String empty = domain == Check.Domain.PROPERTIES && !estate ? "L'immobilier n'est pas ouvert pour votre compte." : "Rien à signaler pour l'instant.";
                if (ignored.stream().anyMatch(check -> check.domain() == domain)) empty = "Les points de cette rubrique sont dans « Points ignorés » ci-dessous.";
                if (checks.stream().anyMatch(check -> check.domain() == domain)) return Html.EMPTY;
                return Components.card(domain.label(), null, Html.EMPTY, Html.of("<p class=\"muted\">%s</p>", empty));
            }
            return Html.of("<section class=\"card section\"><div class=\"section-title\"><h2>%s</h2><span class=\"muted\">%s</span></div><ul class=\"health-list\">%s</ul></section>",
                    domain.label(), domain.summary(), own.stream().map(check -> item(check, false, csrf)).collect(Html.joining()));
        }).collect(Html.joining());
        Html content = Html.concat(
                Components.heading(owner, "Bilan de santé", "Un diagnostic automatique de vos finances, de vos biens et de vos impôts, à partir de ce qui est saisi.",
                        Components.pdfButton("/bilan.pdf")),
                tiles,
                priority("À traiter en priorité", Check.Level.BAD, checks, csrf),
                priority("À surveiller", Check.Level.WARN, checks, csrf),
                domains,
                checks.stream().noneMatch(check -> check.level() == Check.Level.GOOD) ? Html.EMPTY : Components.disclosure("Points rassurants", Html.of("<ul class=\"health-list\">%s</ul>",
                        checks.stream().filter(check -> check.level() == Check.Level.GOOD).map(check -> item(check, false, csrf)).collect(Html.joining())), false),
                Html.of("""
                        <section class="card section" id="ignores"><details%s><summary>Points ignorés (%s)</summary>
                        <p class="muted">Ces choix ne concernent que votre compte et ce foyer. Les points ignorés ne comptent plus dans le résumé ni les compteurs.
                        Une alerte dont la gravité augmente réapparaît. Les points ignorés restent mentionnés dans le PDF.</p>%s</details></section>""",
                        Html.raw(ignored.isEmpty() ? "" : " open"), ignored.size(), ignored.isEmpty()
                                ? Html.raw("<p class=\"muted\">Aucun point ignoré actuellement.</p>")
                                : Html.of("<ul class=\"health-list\">%s</ul>", ignored.stream().map(check -> item(check, true, csrf)).collect(Html.joining()))),
                Html.raw("<p class=\"source\">Repères indicatifs, calculés sur les 12 prochains mois et les données saisies : ils ne remplacent pas un conseiller. "
                        + "Plus les données sont à jour (revue annuelle), plus le bilan est juste.</p>"));
        return Layout.page("Bilan de santé · Patria", Layout.Section.HEALTH, csrf, content);
    }

    private static Html priority(String title, Check.Level level, List<Check> checks, String csrf) {
        List<Check> own = checks.stream().filter(check -> check.level() == level).toList();
        return own.isEmpty() ? Html.EMPTY : Components.card(title, own.size(), Html.EMPTY,
                Html.of("<ul class=\"health-list\">%s</ul>", own.stream().map(check -> item(check, false, csrf)).collect(Html.joining())));
    }

    private static Html item(Check check, boolean ignored, String csrf) {
        Html action = !check.canIgnore() ? Html.EMPTY : Html.of("""
                <form method="post" action="/bilan/%s">%s<input type="hidden" name="check" value="%s">
                <button class="%s" type="submit">%s</button></form>""",
                ignored ? "retablir" : "ignorer", Components.csrfField(csrf), check.token(), ignored ? "secondary" : "quiet", ignored ? "Rétablir" : "Ignorer pour moi");
        return Html.of("<li class=\"health-item %s\"><span class=\"health-mark\" aria-label=\"%s\">%s</span><div><b>%s</b><p>%s</p>%s%s</div></li>",
                css(check.level()), check.level().label(), check.level().mark(), check.title(), check.detail(),
                check.link() == null ? Html.EMPTY : Html.of("<a href=\"%s\">%s →</a>", check.link(), check.linkLabel()), action);
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
        String text = (bad > 0 ? Format.plural(bad, "point à corriger", "points à corriger") : "")
                + (bad > 0 && warn > 0 ? ", " : "") + (warn > 0 ? Format.plural(warn, "point à surveiller", "points à surveiller") : "");
        return Html.of("<section class=\"notice %s health-summary\"><div><b>Bilan de santé</b> : %s.</div><a class=\"button secondary\" href=\"/bilan\">Voir le bilan</a></section>",
                bad > 0 ? "error" : "warning", text);
    }

    static byte[] pdf(String owner, List<Check> checks, List<Check> ignored, LocalDate today) {
        PdfReport report = PdfReport.create("Bilan de santé", owner, today).metrics(Arrays.stream(Check.Level.values())
                .map(level -> PdfReport.Metric.of(level.label(), String.valueOf(checks.stream().filter(check -> check.level() == level).count()), "points")).toList());
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
