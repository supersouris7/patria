package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * « Creer un compte » depuis la page de connexion. Seul l'administrateur cree les comptes : selon son reglage, la page
 * dit simplement de le lui demander, ou propose un formulaire dont les demandes arrivent dans Administration →
 * Demandes de compte, ou il les accepte (compte cree avec un mot de passe provisoire) ou les supprime.
 */
public final class SignupController extends AccountSupport {
    /** Au-dela, les nouvelles demandes sont refusees (protection contre l'envoi en masse). */
    static final int PENDING_LIMIT = 30;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Au plus 3 demandes par adresse IP et par heure (formulaire public). */
    static final int PER_ADDRESS = 3;
    private final java.util.Map<String, java.util.List<LocalDateTime>> recent = new java.util.concurrent.ConcurrentHashMap<>();

    public SignupController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.publicGet("/inscription", request -> Response.page(page(accounts.signupMode(), null)));
        router.publicPost("/inscription", this::send);
        router.get("/administration/demandes", request -> Response.page(adminPage(request.confirmation(), request.csrf())));
        router.post("/administration/demandes/reglage", request -> {
            String mode = request.form().raw("mode");
            accounts.setSignupMode("form".equals(mode) ? "form" : "message");
            return Response.redirect("/administration/demandes?ok=enregistre");
        });
        router.post("/administration/demandes/{id}/accepter", this::accept);
        router.post("/administration/demandes/{id}/supprimer", request -> {
            accounts.deleteRequest(request.id());
            return Response.redirect("/administration/demandes?ok=supprime");
        });
    }

    private Response send(Request request) {
        if (!"form".equals(accounts.signupMode())) return Response.redirect("/inscription");
        // Champ invisible : rempli seulement par les robots.
        if (!request.form().raw("site").isBlank()) return Response.page(page("form", Notice.success("Demande envoyée à l'administrateur.")));
        LocalDateTime now = LocalDateTime.now(clock);
        java.util.List<LocalDateTime> sent = recent.compute(request.clientAddress(), (address, times) -> {
            java.util.List<LocalDateTime> kept = new java.util.ArrayList<>(times == null ? java.util.List.of() : times);
            kept.removeIf(time -> time.isBefore(now.minusHours(1)));
            return kept;
        });
        if (sent.size() >= PER_ADDRESS) {
            return new Response.Page(429, page("form", Notice.error("Trop de demandes depuis cette adresse : réessayez dans une heure.")));
        }
        sent.add(now);
        if (recent.size() > 10_000) recent.clear();
        try {
            String name = request.form().requiredText("name", "Votre nom", 60);
            String login = login(request.form().raw("login"));
            String message = request.form().raw("message").trim();
            if (message.length() > 500) throw new ValidationException("Message : 500 caractères au plus.");
            if (accounts.loginTaken(login)) throw new ValidationException("Cet identifiant est déjà pris : choisissez-en un autre.");
            if (!accounts.addRequest(name, login, message, LocalDateTime.now(clock).withNano(0).toString(), PENDING_LIMIT)) {
                throw new ValidationException("Demande impossible pour l'instant (identifiant déjà demandé ou trop de demandes en attente). Contactez l'administrateur.");
            }
            return Response.page(page("form", Notice.success("Demande envoyée. L'administrateur vous communiquera votre mot de passe provisoire.")));
        } catch (ValidationException exception) {
            return Response.invalid(page("form", Notice.error(exception.getMessage())));
        }
    }

    /** Accepte une demande : le compte est cree avec le mot de passe provisoire saisi, la demande disparait. */
    private Response accept(Request request) {
        AccountRepository.AccountRequest wanted = accounts.requests().stream().filter(candidate -> candidate.id() == request.id()).findFirst()
                .orElseThrow(() -> new fr.patria.common.NotFoundException("Demande introuvable", "Cette demande n'existe plus."));
        try {
            String login = login(wanted.login());
            long id = accounts.createUser(login, wanted.name(), request.form().raw("password"), LocalDate.now(clock));
            accounts.deleteRequest(wanted.id());
            audit("USER", null, id, "Compte « " + login + " » créé à sa demande (mot de passe provisoire).");
            return Response.redirect("/administration/utilisateur/" + id + "?ok=utilisateur");
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    /** Page publique : un message, ou le formulaire de demande. */
    static Html page(String mode, Notice notice) {
        Html body = "form".equals(mode) ? Html.of("""
                <p class="muted">Seul l'administrateur crée les comptes. Envoyez-lui votre demande : il vous communiquera un mot de passe provisoire.</p>
                <form method="post" action="/inscription" class="stack"><label>Votre nom<input name="name" maxlength="60" required autocomplete="name"></label>
                <label>Identifiant souhaité<input name="login" maxlength="40" required autocapitalize="none" spellcheck="false" autocomplete="username"></label>
                <label>Message pour l'administrateur (facultatif)<textarea name="message" maxlength="500" rows="3"></textarea></label>
                <label class="honeypot" aria-hidden="true">Site<input name="site" tabindex="-1" autocomplete="off"></label>
                <button type="submit">Envoyer la demande</button></form>""")
                : Html.raw("<p>Seul l'administrateur de ce Patria crée les comptes. <b>Demandez-lui de vous créer un compte</b> : il vous donnera un "
                        + "identifiant et un mot de passe provisoire.</p>");
        return Layout.document("Créer un compte · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1 class="login-title">Créer un compte</h1>%s%s
                <p class="muted"><a href="/connexion">Retour à la connexion</a></p></main>""", Layout.brand(), Components.notice(notice), body));
    }

    private Html adminPage(Notice notice, String csrf) {
        String mode = accounts.signupMode();
        List<AccountRepository.AccountRequest> requests = accounts.requests();
        Html setting = Html.of("""
                <form method="post" action="/administration/demandes/reglage" class="stack">%s
                <label class="check option"><input type="radio" name="mode" value="message"%s><span><b>Message seul</b><span class="help">« Créer un compte »
                affiche : demandez à l'administrateur de vous créer un compte.</span></span></label>
                <label class="check option"><input type="radio" name="mode" value="form"%s><span><b>Formulaire de demande</b><span class="help">Nom, identifiant
                souhaité et message ; les demandes arrivent ci-dessous (%s au plus en attente).</span></span></label>
                <div class="form-actions"><button type="submit" class="secondary">Enregistrer</button></div></form>""",
                Components.csrfField(csrf), Html.raw("form".equals(mode) ? "" : " checked"), Html.raw("form".equals(mode) ? " checked" : ""), PENDING_LIMIT);
        Html rows = requests.stream().map(item -> Html.of("""
                <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td><form method="post" action="/administration/demandes/%s/accepter" class="row-form">%s\
                <input name="password" type="password" autocomplete="new-password" minlength="8" required placeholder="Mot de passe provisoire" \
                aria-label="Mot de passe provisoire pour %s"><button type="submit">Créer le compte</button></form>\
                <form method="post" action="/administration/demandes/%s/supprimer">%s<button type="submit" class="danger">Supprimer</button></form></td></tr>""",
                item.name(), item.login(), item.message().isBlank() ? "—" : item.message(), format(item.createdAt()), item.id(), Components.csrfField(csrf),
                item.name(), item.id(), Components.csrfField(csrf))).collect(Html.joining());
        Html list = requests.isEmpty() ? Html.raw("<p class=\"empty\">Aucune demande en attente.</p>")
                : Components.table(List.of("Nom", "Identifiant", "Message", "Reçue le", ""), rows, false, false);
        Html content = Html.concat(Components.heading("Administration", "Demandes de compte",
                        "Ce que propose « Créer un compte » sur la page de connexion, et les demandes reçues.", Html.EMPTY),
                Components.notice(notice), Components.card("Page de connexion", null, Html.EMPTY, setting),
                Components.card("Demandes en attente", requests.size(), Html.EMPTY, Html.concat(list, Html.raw("""
                        <p class="muted small">Créer le compte : il s'ouvre avec ce mot de passe provisoire, à changer à la première connexion. Donnez-le
                        à la personne, puis cochez ce qu'elle voit dans le tableau des droits de son foyer (Foyers).</p>"""))));
        return Layout.adminSection("Demandes de compte · Patria", csrf, Layout.AdminSection.REQUESTS, content);
    }

    private static String format(String createdAt) {
        try {
            return LocalDateTime.parse(createdAt).format(WHEN);
        } catch (RuntimeException exception) {
            return createdAt;
        }
    }
}
