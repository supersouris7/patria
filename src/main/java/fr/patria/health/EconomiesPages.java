// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Page « Economies » : score, defi du mois, contrats, economies realisees, objectifs, badges. HTML uniquement. */
final class EconomiesPages {
    /** Illustrations proposees pour un objectif d'epargne. */
    static final List<String> EMOJIS = List.of("🎯", "🏖️", "🚗", "🏠", "🎓", "💍", "🎁", "🛠️", "✈️", "🧸");

    private EconomiesPages() {
    }

    static Html page(EconomiesController.State state, Notice notice, String csrf) {
        Html tiles = Components.metrics(3,
                tile("Santé financière", "Sur 100 : " + String.join(" ; ", state.score().parts()) + ". À ne pas confondre avec le niveau, qui mesure vos progrès.",
                        state.score().value() + " / 100", "accent",
                        state.score().level()),
                tile("Économies réalisées", "Contrats renégociés, sur un an.", Format.euroWhole(state.saved()) + " par an",
                        state.saved().signum() > 0 ? "positive" : "", state.done().isEmpty() ? "renégociez un premier contrat" : state.done().size() + " contrat"
                                + (state.done().size() > 1 ? "s" : "")),
                tile("À gagner", "Écart entre vos contrats et des offres courantes, d'après des repères datés. Indicatif.",
                        state.potential().signum() > 0 ? "jusqu'à " + Format.euroWhole(state.potential()) + " par an" : "rien d'évident",
                        state.potential().signum() > 0 ? "warning" : "positive", "sur les contrats reconnus"));
        Html content = Html.concat(Components.heading("Économies", "Économies", "Où vous pouvez payer moins, et ce que vous avez déjà gagné.", Html.EMPTY),
                Components.notice(notice), level(state), tiles, challenge(state.challenge()), missions(state.missions()), streaks(state.streaks()),
                contracts(state, csrf), done(state, csrf), goals(state, csrf), badges(state.badges()),
                Html.of("<p class=\"muted small\">Repères vérifiés le %s : tarif réglementé de l'électricité (août 2026), Arcep (box et mobile), Observatoire des tarifs bancaires, "
                        + "études 2026 sur les assurances et les mutuelles. Indicatifs, pas un conseil personnalisé. Rien n'est envoyé à un comparateur.</p>",
                        Format.date(Contracts.CHECKED)));
        return Layout.page("Économies · Patria", Layout.Section.ECONOMIES, csrf, content);
    }

    /**
     * Niveau et points : la barre avance vers le niveau suivant. Les attributs {@code data-celebrate} listent ce qui est
     * obtenu : app.js celebre (discretement) ce qui vient d'etre gagne depuis la derniere visite.
     */
    private static Html level(EconomiesController.State state) {
        Progression.Level level = state.level();
        String earned = String.join(",", java.util.stream.Stream.concat(java.util.stream.Stream.of("niveau-" + level.number()),
                java.util.stream.Stream.concat(state.badges().stream().filter(EconomiesController.Badge::earned).map(badge -> "badge-" + badge.name()),
                        state.missions().stream().filter(Progression.Mission::done).map(mission -> "mission-" + mission.title()))).toList());
        return Html.of("""
                <section class="card level-card" data-celebrate="%s"><div class="level-badge" aria-hidden="true">%s</div><div class="level-text">\
                <p class="eyebrow">Niveau %s</p><h2>%s</h2><div class="progress level-progress" role="progressbar" aria-valuenow="%s" aria-valuemin="0" aria-valuemax="100">\
                <span style="width:%s%"></span></div><p class="muted small">%s points%s</p></div></section>""",
                earned, level.number(), level.number(), level.name(), level.percent(), level.percent(), level.xp(),
                level.next() == null ? " · niveau maximal" : " · encore " + (level.next() - level.xp()) + " pour le niveau suivant");
    }

    /** Missions : plusieurs etapes cochees au fil de la saisie, et la recompense en points. */
    private static Html missions(List<Progression.Mission> missions) {
        Html cards = missions.stream().map(mission -> Html.of("""
                <li class="mission%s"><div class="mission-head"><span class="mission-icon" aria-hidden="true">%s</span><div><b>%s</b><small>%s</small></div>\
                <span class="badge%s">%s</span></div><div class="progress"><span style="width:%s%"></span></div><ul class="mission-steps">%s</ul></li>""",
                mission.done() ? " done" : "", mission.icon(), mission.title(), mission.goal(), mission.done() ? " paid" : "",
                mission.done() ? "Réussie · +" + mission.reward() + " pts" : "+" + mission.reward() + " pts", mission.percent(),
                mission.steps().stream().map(step -> Html.of("<li class=\"%s\"><span aria-hidden=\"true\">%s</span>%s</li>", step.done() ? "done" : "",
                        step.done() ? "✓" : "○", step.label())).collect(Html.joining()))).collect(Html.joining());
        return Components.card("Missions", null, Html.of("<span class=\"muted\">%s sur %s réussies</span>", missions.stream().filter(Progression.Mission::done).count(),
                missions.size()), Html.of("<ul class=\"missions\">%s</ul>", cards));
    }

    /** Series en cours ; une serie cassee repart simplement de zero. */
    private static Html streaks(List<Progression.Streak> streaks) {
        Html items = streaks.stream().map(streak -> Html.of("<li class=\"streak%s\"><span aria-hidden=\"true\">%s</span><b>%s</b><small>%s</small><em>%s</em></li>",
                streak.current() > 0 ? " on" : "", streak.icon(), streak.current() == 0 ? "—" : streak.current() + " mois", streak.label(), streak.how())).collect(Html.joining());
        return Components.card("Séries", null, Html.raw("<span class=\"muted\">mois d'affilée</span>"), Html.of("<ul class=\"streaks\">%s</ul>", items));
    }

    private static Html tile(String label, String hint, String value, String tone, String sub) {
        return Html.of("<article class=\"metric\">%s<strong class=\"%s\">%s</strong><small>%s</small></article>", Components.label(label, hint), tone, value, sub);
    }

    private static Html challenge(EconomiesController.Challenge challenge) {
        return Html.of("""
                <section class="card guide-next challenge%s"><p class="eyebrow">Défi du mois</p><h2>%s</h2><p class="muted">%s</p>%s</section>""",
                challenge.done() ? " done" : "", challenge.title(), challenge.detail(),
                challenge.href() == null ? Html.EMPTY : Html.of("<div class=\"form-actions\"><a class=\"button\" href=\"%s\">%s</a></div>", challenge.href(), challenge.action()));
    }

    private static Html contracts(EconomiesController.State state, String csrf) {
        Html rows = state.contracts().stream().map(contract -> {
            String id = "contrat-" + contract.kind().name().toLowerCase(Locale.ROOT);
            Html steps = contract.steps().stream().map(step -> Html.of("<li>%s</li>", step)).collect(Html.joining());
            Html link = contract.link() == null ? Html.EMPTY
                    : Html.of("<p><a href=\"%s\" target=\"_blank\" rel=\"noopener noreferrer\">%s ↗</a></p>", contract.link(), contract.linkLabel());
            Html form = !state.canEdit() ? Html.EMPTY : Html.of("""
                    <form method="post" action="/economies/renegocie" class="row-form">%s<input type="hidden" name="contrat" value="%s">\
                    <label>C'est fait ? Nouveau montant par mois (€)<input name="nouveau" inputmode="decimal" required placeholder="%s"></label>\
                    <button class="secondary" type="submit">J'ai renégocié</button></form>%s""",
                    Components.csrfField(csrf), contract.kind().name(), Format.number(contract.monthly(), 2),
                    contract.items().size() > 1 ? Html.raw("<p class=\"muted small\">Plusieurs lignes : l'économie est comptée ; ajustez les montants dans Gérer → Dépenses.</p>")
                            : Html.raw("<p class=\"muted small\">Le budget prend le nouveau montant à partir du mois prochain.</p>"));
            return Html.of("""
                    <details class="contract" id="%s"><summary><span class="contract-name"><b>%s</b><small>%s par mois · %s</small></span>%s</summary>\
                    <div class="contract-body"><p><b>%s</b></p><p class="muted small">Repère : %s</p><ol class="howto-steps">%s</ol>%s%s</div></details>""",
                    id, contract.kind().label, Format.euroWhole(contract.monthly()), String.join(", ", contract.items().stream().map(item -> item.label()).toList()),
                    contract.above() ? Html.of("<span class=\"badge warning-badge\">jusqu'à %s / an</span>", Format.euroWhole(contract.yearlyGain()))
                            : Html.raw("<span class=\"badge paid\">dans les repères</span>"),
                    contract.verdict(), contract.benchmark(), steps, link, form);
        }).collect(Html.joining());
        Html loans = state.loans().stream().map(loan -> Html.of("""
                <details class="contract" id="contrat-pret-%s"><summary><span class="contract-name"><b>Assurance de prêt</b><small>%s</small></span>\
                <span class="badge">à comparer</span></summary><div class="contract-body">\
                <p><b>Souvent le plus gros gain possible.</b> Depuis 2022 (loi Lemoine), l'assurance d'un prêt immobilier se change à tout moment, sans frais.</p>\
                <ol class="howto-steps"><li>Retrouvez le coût mensuel de votre assurance sur l'offre de prêt ou l'échéancier.</li>\
                <li>Demandez des devis à des assureurs en délégation, avec des garanties au moins équivalentes à celles exigées par la banque.</li>\
                <li>Envoyez le nouveau contrat à la banque : elle a 10 jours ouvrés pour répondre et ne peut refuser que si les garanties ne sont pas équivalentes.</li></ol>%s</div></details>""",
                loan.id(), loan.name(), !state.canEdit() ? Html.EMPTY : Html.of("""
                        <form method="post" action="/economies/renegocie" class="row-form">%s<input type="hidden" name="contrat" value="pret-%s">\
                        <label>Ancien coût par mois (€)<input name="ancien" inputmode="decimal" required></label>\
                        <label>Nouveau coût par mois (€)<input name="nouveau" inputmode="decimal" required></label>\
                        <button class="secondary" type="submit">J'ai changé d'assurance</button></form>""", Components.csrfField(csrf), loan.id()))).collect(Html.joining());
        Html empty = state.contracts().isEmpty() && state.loans().isEmpty()
                ? Html.raw("<p class=\"muted\">Aucun contrat reconnu pour l'instant. Saisissez vos dépenses d'électricité, box, mobile, assurances et mutuelle "
                + "(Gérer → Dépenses, ou le Guide de saisie) : Patria les comparera à des repères.</p>") : Html.EMPTY;
        return Html.of("<div id=\"contrats\">%s</div>", Components.card("Vos contrats", state.contracts().size() + state.loans().size(), Html.EMPTY,
                Html.concat(Html.raw("<p class=\"muted\">Chaque contrat est comparé à un repère ; ouvrez-le pour la démarche, pas à pas.</p>"), empty, rows, loans)));
    }

    private static Html done(EconomiesController.State state, String csrf) {
        if (state.done().isEmpty()) return Html.EMPTY;
        Html rows = state.done().stream().map(economy -> Html.of("""
                <li><span><b>%s</b> · %s → %s par mois <span class="muted">(%s)</span></span><span class="positive">+%s / an</span>%s</li>""",
                economy.label(), Format.euro(economy.before()), Format.euro(economy.after()), Format.date(economy.doneOn()),
                Format.euroWhole(economy.monthly().multiply(BigDecimal.valueOf(12))),
                !state.canEdit() ? Html.EMPTY : Html.of("<form method=\"post\" action=\"/economies/%s/supprimer\">%s<button class=\"quiet small\" type=\"submit\" "
                        + "aria-label=\"Retirer\">×</button></form>", economy.id(), Components.csrfField(csrf)))).collect(Html.joining());
        return Html.of("<div id=\"realisees\">%s</div>", Components.card("Économies réalisées", state.done().size(), Html.EMPTY,
                Html.of("<ul class=\"plain-list economies-done\">%s</ul>", rows)));
    }

    private static Html goals(EconomiesController.State state, String csrf) {
        Html rows = state.goals().stream().map(goal -> {
            BigDecimal saved = goal.accountId() == null ? BigDecimal.ZERO : state.balances().getOrDefault(goal.accountId(), BigDecimal.ZERO);
            int percent = goal.target().signum() == 0 ? 0 : Math.min(100, saved.multiply(BigDecimal.valueOf(100)).divide(goal.target(), 0, RoundingMode.DOWN).intValue());
            return Html.of("""
                    <li class="goal"><span class="goal-emoji" aria-hidden="true">%s</span><div><b>%s</b><small>%s sur %s%s</small>%s</div>%s</li>""",
                    goal.emoji(), goal.name(), Format.euroWhole(saved), Format.euroWhole(goal.target()),
                    goal.accountId() == null ? "" : " · " + state.accounts().getOrDefault(goal.accountId(), "compte supprimé"), Components.progress(percent),
                    !state.canEdit() ? Html.EMPTY : Html.of("<form method=\"post\" action=\"/economies/objectifs/%s/supprimer\">%s<button class=\"quiet small\" "
                            + "type=\"submit\" aria-label=\"Supprimer\">×</button></form>", goal.id(), Components.csrfField(csrf)));
        }).collect(Html.joining());
        Html options = state.accounts().entrySet().stream().map(entry -> Html.of("<option value=\"%s\">%s</option>", entry.getKey(), entry.getValue())).collect(Html.joining());
        Html emojis = EMOJIS.stream().map(emoji -> Html.of("<option value=\"%s\">%s</option>", emoji, emoji)).collect(Html.joining());
        Html form = !state.canEdit() ? Html.EMPTY : Html.of("""
                <form method="post" action="/economies/objectifs" class="row-form">%s\
                <label>Image<select name="emoji">%s</select></label>\
                <label>Objectif<input name="nom" maxlength="60" required placeholder="Vacances 2027"></label>\
                <label>Montant visé (€)<input name="montant" inputmode="decimal" required placeholder="2 000"></label>\
                <label>Compte qui le finance<select name="compte"><option value="">Aucun</option>%s</select></label>\
                <button class="secondary" type="submit">Ajouter</button></form>""", Components.csrfField(csrf), emojis, options);
        return Html.of("<div id=\"objectifs\">%s</div>", Components.card("Objectifs d'épargne", state.goals().size(), Html.EMPTY, Html.concat(
                state.goals().isEmpty() ? Html.raw("<p class=\"muted\">Un objectif concret (vacances, voiture, travaux) se remplit à mesure que son livret grossit.</p>")
                        : Html.of("<ul class=\"plain-list goals\">%s</ul>", rows), form)));
    }

    private static Html badges(List<EconomiesController.Badge> badges) {
        long earned = badges.stream().filter(EconomiesController.Badge::earned).count();
        Html items = badges.stream().map(badge -> Html.of("<li class=\"badge-card%s\"><span aria-hidden=\"true\">%s</span><b>%s</b><small>%s</small></li>",
                badge.earned() ? " earned" : "", badge.icon(), badge.name(), badge.earned() ? "Obtenu" : badge.how())).collect(Html.joining());
        return Components.card("Badges", null, Html.of("<span class=\"muted\">%s sur %s</span>", earned, badges.size()),
                Html.of("<ul class=\"badge-grid\">%s</ul>", items));
    }
}
