package fr.patria.account;

import fr.patria.ui.BuildInfo;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import java.util.List;

/** Accueil de la premiere connexion : quatre ecrans dans un encadre jaune, avec « Suivant ». HTML uniquement. */
final class WelcomePages {
    static final int STEPS = 4;

    private WelcomePages() {
    }

    /**
     * Ecran {@code step} (1 a 4). {@code manages} : utilisateur d'un foyer (il saisit) ; {@code administrator} :
     * l'administrateur (son role, sans budget) ;
     * {@code encrypted} : bases chiffrees (DB_ENCRYPTION_KEY).
     */
    static Html page(int step, String name, boolean manages, boolean administrator, boolean encrypted, String csrf) {
        Html body = switch (step) {
            case 1 -> administrator ? administration(name) : features(name);
            case 2 -> people();
            case 3 -> privacy(administrator, encrypted);
            default -> support(manages, administrator);
        };
        List<String> titles = List.of("Ce que fait Patria", "Pour qui", "Confidentialité", "Soutenir");
        Html dots = Html.of("<ol class=\"welcome-steps\" aria-label=\"Étapes de l'accueil\">%s</ol>", java.util.stream.IntStream.rangeClosed(1, STEPS)
                .mapToObj(index -> Html.of("<li class=\"%s\"%s><span>%s</span>%s</li>", index == step ? "current" : index < step ? "done" : "",
                        Html.raw(index == step ? " aria-current=\"step\"" : ""), index, titles.get(index - 1))).collect(Html.joining()));
        Html previous = step > 1 ? Html.of("<a class=\"button secondary\" href=\"/bienvenue?etape=%s\">Précédent</a>", step - 1) : Html.EMPTY;
        Html next = step < STEPS ? Html.of("<a class=\"button\" href=\"/bienvenue?etape=%s\">Suivant</a>", step + 1)
                : Html.of("<form method=\"post\" action=\"/bienvenue/terminer\">%s<button type=\"submit\">%s</button></form>", Components.csrfField(csrf),
                administrator ? "Ouvrir l'administration" : manages ? "Commencer avec le Guide de saisie" : "Commencer");
        Html skip = step < STEPS ? Html.of("""
                <form method="post" action="/bienvenue/terminer" class="welcome-skip">%s<input type="hidden" name="passer" value="1">\
                <button class="quiet" type="submit">Passer l'accueil</button></form>""", Components.csrfField(csrf)) : Html.EMPTY;
        Html content = Html.of("""
                <section class="welcome notice warning" aria-labelledby="welcome-title">%s%s
                <div class="welcome-actions">%s<span class="welcome-count">%s / %s</span>%s</div>%s</section>""",
                dots, body, previous, step, STEPS, next, skip);
        return Layout.page("Bienvenue · Patria", Layout.Section.NONE, csrf, content);
    }

    /** Premier ecran de l'administrateur : son role, sans budget. */
    private static Html administration(String name) {
        return Html.of("""
                <p class="eyebrow">Première connexion</p><h1 id="welcome-title">Bienvenue, %s</h1>
                <p class="lead">Vous administrez cette installation de Patria. Vous n'avez pas de budget et ne voyez aucune donnée financière :
                dans l'interface avec votre compte administrateur : ce sont les comptes des foyers qui saisissent et consultent.</p>
                <div class="welcome-grid">
                <div><b>Foyers</b><span>Créez un foyer par budget, ajoutez ses membres, suivez l'avancement de sa configuration.</span></div>
                <div><b>Utilisateurs</b><span>Vous seul créez les comptes et réglez ce que chacun voit. Le mot de passe provisoire est changé à la première connexion.</span></div>
                <div><b>Sécurité</b><span>Connexions échouées, blocages, journal des droits, état de l'installation (HTTPS, chiffrement).</span></div>
                <div><b>Transparence</b><span>Chaque accès que vous ouvrez est visible par les comptes du foyer. Pour votre propre budget, créez-vous un compte.</span></div>
                </div>""", name);
    }

    private static Html features(String name) {
        return Html.of("""
                <p class="eyebrow">Première connexion</p><h1 id="welcome-title">Bienvenue sur Patria, %s</h1>
                <p class="lead">Patria rassemble le budget et le patrimoine immobilier de votre foyer, et vous dit où vous en serez dans les mois qui viennent.</p>
                <div class="welcome-grid">
                <div><b>Prévisionnel</b><span>Les 12 prochains mois, mois par mois : ce qui rentre, ce qui sort, les mois en déficit.</span></div>
                <div><b>Budget moyen</b><span>Combien coûte chaque poste en moyenne par mois : logement, enfants, abonnements…</span></div>
                <div><b>Immobilier</b><span>Biens, prêts, loyers, valeur estimée, et les ventes réelles du quartier (DVF).</span></div>
                <div><b>Bilan de santé</b><span>Taux d'endettement, épargne de précaution, impôts à ne pas oublier, avec quoi corriger.</span></div>
                <div><b>Guide de saisie</b><span>Les documents à rassembler et chaque étape pour tout renseigner, puis une revue chaque année.</span></div>
                <div><b>Rapports PDF</b><span>Une synthèse à imprimer ou à envoyer à votre banquier, page par page.</span></div>
                </div>""", name);
    }

    private static Html people() {
        return Html.raw("""
                <p class="eyebrow">Pour qui</p><h1 id="welcome-title">Votre famille, vos proches, vos amis</h1>
                <p class="lead">Une seule installation suffit pour plusieurs foyers : le vôtre, celui de vos parents, d'un ami que vous aidez.</p>
                <ul class="welcome-list">
                <li><b>Un foyer par budget</b>, séparé des autres : ses membres, son compte joint, ses biens.</li>
                <li><b>Des dépenses partagées</b> : chaque ligne se répartit entre les membres (parts égales, ou 60 / 40…).</li>
                <li><b>Chacun ne voit que ce qui le concerne</b> : un compte par personne, avec ses propres lignes. Les totaux du foyer,
                l'immobilier ou la gestion complète s'ouvrent seulement si on le décide.</li>
                <li><b>Le compte administrateur n'ouvre aucune finance dans l'interface</b> : il crée les comptes et règle les droits. Les accès sont visibles
                avec l'icône jaune « qui voit quoi ». L'exploitant du serveur peut techniquement lire les données : choisissez un hébergeur de confiance.</li>
                </ul>""");
    }

    private static Html privacy(boolean administrator, boolean encrypted) {
        Html encryption = encrypted ? Html.raw("<li><b>Bases chiffrées</b> : sans la clé du serveur, les fichiers et leurs sauvegardes sont illisibles.</li>")
                : administrator ? Html.raw("""
                <li><b>Bases non chiffrées</b> : définissez <code>DB_ENCRYPTION_KEY</code> dans la configuration pour chiffrer les fichiers et leurs
                sauvegardes <b>avant toute saisie</b> ; conservez la clé séparément, sa perte est irréversible
                (<a href="/aide/chiffrement">pas à pas</a>).</li>""")
                : Html.raw("<li><b>Bases non chiffrées</b> : demandez à l'hébergeur d'activer le chiffrement avant de saisir des données sensibles.</li>");
        return Html.of("""
                <p class="eyebrow">Confidentialité</p><h1 id="welcome-title">Vos données hébergées chez vous</h1>
                <p class="lead">Pas de synchronisation bancaire ni d'envoi de vos chiffres par Patria. La protection dépend aussi de l'hébergement,
                du HTTPS, du chiffrement des bases et des droits accordés.</p>
                <ul class="welcome-list">
                <li><b>Aucun compte en ligne, aucune publicité, aucun traceur</b> : pas de service tiers qui lit vos données.</li>
                %s
                <li><b>Appels extérieurs</b> : adresse ou commune d'un bien vers l'API Adresse, codes géographiques vers DVF, requête sans chiffres personnels
                vers la BCE et l'INSEE, codes des valeurs de bourse vers Yahoo Finance, et IP des connexions vers ipwho.is pour leur localisation. Le mode hors ligne coupe ces appels.</li>
                <li><b>L'IA est facultative</b> : préparez vos CSV vous-même ou utilisez une IA. Avant tout envoi, créez une copie caviardée
                (« Préparer un document » si votre installation le propose) et vérifiez chaque page. N'envoyez pas l'original.</li>
                </ul><p><a href="/confidentialite">Tout savoir sur la confidentialité</a></p>""", encryption);
    }

    private static Html support(boolean manages, boolean administrator) {
        String url = BuildInfo.supportUrl();
        Html button = url == null ? Html.EMPTY
                : Html.of("""
                <p><a class="button kofi" href="%s" target="_blank" rel="noopener noreferrer">♥ Soutenir Patria sur Ko-fi</a></p>
                <p class="muted small">Le lien ouvre ko-fi.com dans un nouvel onglet ; Patria ne charge rien depuis Ko-fi. Il reste en pied de page.</p>""", url);
        Html start = administrator ? Html.raw("<p>Pour commencer : <b>Foyers</b>, puis un compte par personne dans <b>Utilisateurs</b>, et cochez qui voit quoi dans le tableau des droits de chaque foyer.</p>")
                : manages ? Html.raw("<p>Pour commencer, le <b>Guide de saisie</b> vous accompagne étape par étape, des documents à rassembler jusqu'au récapitulatif.</p>")
                : Html.raw("<p>Pour commencer, ouvrez le <b>Prévisionnel</b> : vos revenus et dépenses y apparaissent dès qu'ils sont saisis.</p>");
        return Html.of("""
                <p class="eyebrow">Soutenir</p><h1 id="welcome-title">Patria est gratuit</h1>
                <p class="lead">Pas d'abonnement, pas de publicité, pas de revente de données. Si Patria vous est utile, un don, même petit, finance le temps
                passé à l'améliorer.</p>%s%s""",
                button, start);
    }
}
