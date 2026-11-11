// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;

/**
 * Type d'installation, choisi par l'administrateur au premier lancement et modifiable ensuite :
 * <ul>
 *   <li>locale (familiale) : inscriptions sur invitation, e-mail facultatif ;</li>
 *   <li>cloud (ouverte au public) : inscriptions ouvertes, e-mail confirme exige des que l'envoi est regle, double
 *   authentification obligatoire pour l'administrateur.</li>
 * </ul>
 * Aussi : l'adresse publique (liens des invitations et des e-mails) et les pages legales (conditions d'utilisation,
 * mentions legales), publiees en pied de page ; les conditions sont a accepter a la creation d'un compte.
 */
public final class InstallationController extends AccountSupport {
    static final int LEGAL_MAX = 20_000;

    public InstallationController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.get("/administration/installation", request -> Response.page(page(request, request.confirmation())));
        router.post("/administration/installation", this::save);
        router.publicGet("/conditions", request -> Response.page(legal(request, "Conditions d'utilisation", accounts.installation().terms())));
        router.publicGet("/mentions-legales", request -> Response.page(legal(request, "Mentions légales", accounts.installation().notice())));
    }

    private Response save(Request request) {
        String mode = request.form().raw("type");
        if (!mode.equals("local") && !mode.equals("cloud")) return Response.invalid(page(request, Notice.error("Choisissez une inscription privée ou publique.")));
        String url = request.form().raw("adresse").strip();
        if (!url.isEmpty() && !url.matches("https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/[A-Za-z0-9._~/-]*)?")) {
            return Response.invalid(page(request, Notice.error("Adresse publique invalide : par exemple https://patria.example.fr")));
        }
        if (mode.equals("cloud") && !url.startsWith("https://")) {
            return Response.invalid(page(request, Notice.error("Pour les inscriptions publiques, indiquez l'adresse publique en https:// (liens des invitations et des e-mails).")));
        }
        String terms = request.form().raw("conditions").strip();
        String notice = request.form().raw("mentions").strip();
        if (terms.length() > LEGAL_MAX || notice.length() > LEGAL_MAX) return Response.invalid(page(request, Notice.error("Texte trop long (20 000 caractères au plus).")));
        AccountRepository.Mail mail = accounts.mail();
        accounts.setMail(new AccountRepository.Mail(mail.smtp(), url, mode.equals("cloud") ? mail.smtp().configured() : mail.required()));
        accounts.setSignupMode(mode.equals("cloud") ? "open" : "invite");
        accounts.setInstallation(new AccountRepository.Installation(mode, terms, notice));
        Layout.useInstallation(mode.equals("cloud"), !terms.isEmpty(), !notice.isEmpty());
        audit("USER", null, null, "Installation réglée : " + (mode.equals("cloud") ? "publique (inscriptions ouvertes)" : "privée (sur invitation)") + ".");
        return Response.redirect("/administration/installation?ok=enregistre");
    }

    private Html page(Request request, Notice notice) {
        AccountRepository.Installation installation = accounts.installation();
        AccountRepository.Mail mail = accounts.mail();
        boolean first = installation.mode().isEmpty();
        Html warnings = Html.concat(
                installation.cloud() && !twoFactors.twoFactor(null).enabled() ? Html.raw("<p class=\"notice error\"><b>Inscriptions publiques</b> : activez la "
                        + "<a href=\"/compte/double-authentification\">double authentification de l'administrateur</a> pour accéder aux listes de foyers et d'utilisateurs.</p>")
                        : Html.EMPTY,
                installation.cloud() && !mail.active() ? Html.raw("<p class=\"notice warning\">Réglez l'<a href=\"/administration/securite#email\">envoi d'e-mails</a> : "
                        + "puis cochez « Exiger un e-mail confirmé » pour vérifier l'adresse des nouveaux comptes.</p>") : Html.EMPTY,
                installation.cloud() && mail.active() && !mail.required() ? Html.raw("<p class=\"notice warning\">La confirmation d'e-mail n'est pas exigée. Activez-la dans "
                        + "<a href=\"/administration/securite#email\">Sécurité → E-mail</a> pour les nouveaux comptes.</p>") : Html.EMPTY);
        Html form = Html.of("""
                <form method="post" action="/administration/installation" class="stack">%s
                <div class="install-choices">
                <label class="install-choice"><input type="radio" name="type" value="local"%s><span><b>Privée — sur invitation</b>
                Pour vos proches : les comptes se créent sur invitation. L'e-mail dépend du réglage dans Sécurité.</span></label>
                <label class="install-choice"><input type="radio" name="type" value="cloud"%s><span><b>Publique — inscriptions ouvertes</b>
                Chacun peut créer son compte et son foyer. Adresse HTTPS requise ; activez la double authentification administrateur et la confirmation d'e-mail.</span></label>
                </div>
                <label>Adresse publique de Patria<input name="adresse" value="%s" maxlength="200" placeholder="https://patria.example.fr">\
                <span class="help">Sert aux liens des invitations et des e-mails. Obligatoire en HTTPS pour les inscriptions publiques ; renseigner cette adresse ne configure pas HTTPS.</span></label>
                <details%s><summary>Pages légales (facultatif, conseillé pour une installation publique)</summary>
                <label>Conditions d'utilisation<textarea name="conditions" rows="8" maxlength="20000">%s</textarea>\
                <span class="help">Affichées en pied de page ; à accepter pour créer un compte. Texte simple, un paragraphe par ligne vide.</span></label>
                <label>Mentions légales<textarea name="mentions" rows="6" maxlength="20000">%s</textarea>\
                <span class="help">Éditeur, hébergeur, contact.</span></label></details>
                <div class="form-actions"><button type="submit">%s</button></div></form>""",
                Components.csrfField(request.csrf()), Html.raw(installation.cloud() ? "" : " checked"), Html.raw(installation.cloud() ? " checked" : ""),
                mail.publicUrl(), Html.raw(installation.terms().isEmpty() && installation.notice().isEmpty() ? "" : " open"), installation.terms(), installation.notice(),
                first ? "Commencer" : "Enregistrer");
        Html content = Html.concat(Components.heading("Administration", first ? "Bienvenue : votre installation" : "Installation",
                        "Choisissez qui peut s'inscrire. Ce choix ne change ni l'hébergement ni le chiffrement. Chaque enregistrement remet les inscriptions sur invitation ou ouvertes ; vous pouvez ensuite les modifier dans Utilisateurs.", Html.EMPTY),
                Components.notice(notice), warnings, Components.card("Type d'installation", null, Html.EMPTY, form));
        return Layout.adminSection("Installation · Patria", request.csrf(), Layout.AdminSection.INSTALLATION, content);
    }

    /** Page legale publique : texte de l'administrateur, un paragraphe par bloc separe d'une ligne vide. */
    private Html legal(Request request, String title, String text) {
        if (text.isBlank()) throw new fr.patria.common.NotFoundException("Page introuvable", "Cette page n'existe pas.");
        Html paragraphs = java.util.Arrays.stream(text.replace("\r\n", "\n").split("\n\\s*\n")).map(paragraph -> Html.of("<p>%s</p>", paragraph.strip()))
                .collect(Html.joining());
        return Layout.document(title + " · Patria", "legal-page", Html.of("""
                <main class="shell legal">%s<h1>%s</h1>%s<p class="muted"><a href="/connexion">Retour</a></p></main>""", Layout.brand(), title, paragraphs));
    }

    /** Mode cloud : l'administrateur doit activer sa double authentification avant les pages d'administration. */
    static boolean blocked(AccountRepository accounts, TwoFactorStore twoFactors) {
        return accounts.installation().cloud() && !twoFactors.twoFactor(null).enabled();
    }
}
