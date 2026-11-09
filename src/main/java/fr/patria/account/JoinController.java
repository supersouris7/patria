package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Session;
import fr.patria.web.Sessions;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arrivee d'une personne : chacun cree son propre compte (l'administrateur n'en cree plus).
 * <ul>
 *   <li>Inscription libre (reglage « ouvertes ») : le compte et son propre foyer, dont la personne devient
 *   gestionnaire.</li>
 *   <li>Invitation (lien valable 7 jours, a usage unique) : envoyee par le gestionnaire d'un foyer pour le rejoindre
 *   avec les droits qu'il a choisis, ou par l'administrateur pour creer un nouveau foyer ou reprendre un foyer sans
 *   gestionnaire. Un compte existant peut aussi l'accepter.</li>
 * </ul>
 */
public final class JoinController extends AccountSupport {
    static final int PER_ADDRESS = 5;
    private final Map<String, List<LocalDateTime>> recent = new ConcurrentHashMap<>();

    public JoinController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.publicGet("/inscription", request -> Response.page(signupPage(Map.of(), null)));
        router.publicPost("/inscription", this::signup);
        router.publicGet("/invitation", request -> Response.page(invitationPage(request, request.query("jeton"), Map.of(), null)));
        router.publicPost("/invitation", this::acceptAsNew);
        router.userPost("/invitation/rejoindre", this::acceptAsMember);
    }

    // ---- Inscription libre ----

    private Response signup(Request request) {
        if (!"open".equals(accounts.signupMode())) return Response.redirect("/inscription");
        FormData form = request.form();
        // Champ invisible : rempli seulement par les robots.
        if (!form.raw("site").isBlank()) return Response.redirect("/connexion");
        try {
            limit(request);
            String name = form.requiredText("name", "Votre prénom", 60);
            String householdName = form.raw("foyer").isBlank() ? "Foyer de " + name : form.requiredText("foyer", "Nom du foyer", 60);
            long userId = createAccount(request, form, name);
            Household household = newHousehold(userId, name, householdName);
            audit(name, "USER", household.id(), userId, "Compte « " + accounts.user(userId).orElseThrow().login() + " » créé par inscription, avec son foyer « "
                    + household.name() + " ».");
            return signIn(request, userId, household.id());
        } catch (ValidationException exception) {
            Map<String, String> values = new java.util.HashMap<>(form.values());
            values.remove("password");
            values.remove("confirm");
            return Response.invalid(signupPage(values, Notice.error(exception.getMessage())));
        }
    }

    // ---- Invitations ----

    private Response acceptAsNew(Request request) {
        FormData form = request.form();
        String token = form.raw("jeton");
        LocalDateTime now = LocalDateTime.now(clock);
        AccountRepository.Invitation invitation = accounts.invitation(token, now)
                .orElseThrow(() -> new ValidationException("Invitation expirée ou déjà utilisée : demandez un nouveau lien."));
        try {
            limit(request);
            String name = form.requiredText("name", "Votre prénom", 60);
            long userId = createAccount(request, form, name);
            long household = join(userId, name, invitation, form.raw("foyer"));
            return signIn(request, userId, household);
        } catch (ValidationException exception) {
            Map<String, String> values = new java.util.HashMap<>(form.values());
            values.remove("password");
            values.remove("confirm");
            return Response.invalid(invitationPage(request, token, values, Notice.error(exception.getMessage())));
        }
    }

    /** Compte deja connecte : il rejoint le foyer (ou cree le sien) avec les droits de l'invitation. */
    private Response acceptAsMember(Request request) {
        if (request.viewer().isAdministrator()) throw new ValidationException("Connectez-vous avec votre compte personnel pour accepter une invitation.");
        AccountRepository.Invitation invitation = accounts.invitation(request.form().raw("jeton"), LocalDateTime.now(clock))
                .orElseThrow(() -> new ValidationException("Invitation expirée ou déjà utilisée : demandez un nouveau lien."));
        long household = join(request.viewer().userId(), request.viewer().name(), invitation, request.form().raw("foyer"));
        sessions.select(request.session(), household);
        return Response.redirect("/?ok=foyer-rejoint");
    }

    /**
     * Applique une invitation : nouveau foyer (la personne en est gestionnaire), ou acces au foyer invitant ; un membre
     * a son nom est cree s'il le faut. Les droits deja ouverts sur ce foyer sont gardes et completes.
     */
    private long join(long userId, String name, AccountRepository.Invitation invitation, String householdName) {
        if (!accounts.useInvitation(invitation.id(), userId)) throw new ValidationException("Invitation déjà utilisée : demandez un nouveau lien.");
        if (invitation.householdId() == null) {
            String wanted = householdName == null || householdName.isBlank() ? "Foyer de " + name : householdName.strip();
            if (wanted.length() > 60) throw new ValidationException("Nom du foyer : 60 caractères au plus.");
            Household household = newHousehold(userId, name, wanted);
            audit(invitation.createdBy(), "HOUSEHOLD", household.id(), userId, "Foyer « " + household.name() + " » créé par " + name + " (invitation).");
            return household.id();
        }
        Household household = household(invitation.householdId());
        List<HouseholdDirectory.Member> members = directory.members(household.id());
        Set<Long> seen = new LinkedHashSet<>();
        if (invitation.memberId() != null && members.stream().anyMatch(member -> member.id() == invitation.memberId())) {
            seen.add(invitation.memberId());
        } else if (invitation.memberName() != null && !invitation.memberName().isBlank()) {
            long member = directory.addMember(household.id(), invitation.memberName(), false);
            seen.add(member);
            // Comme un membre ajoute a la main : les gestionnaires le voient (ils peuvent decocher ensuite).
            accounts.accessesTo(household.id()).forEach((manager, rights) -> {
                if (!rights.manager()) return;
                Set<Long> members = new LinkedHashSet<>(rights.members());
                members.add(member);
                accounts.setAccess(manager.id(), household.id(), new Access(manager.id(), household.id(), household.name(), rights.wholeHousehold(), members,
                        rights.realEstate(), true));
            });
        } else if (invitation.manager()) {
            // Reprise d'un foyer sans gestionnaire : il voit tout, puis ajuste ses cases.
            members.forEach(member -> seen.add(member.id()));
        }
        Access before = accounts.accessesTo(household.id()).entrySet().stream().filter(entry -> entry.getKey().id() == userId).map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (before != null) seen.addAll(before.members());
        Access access = new Access(userId, household.id(), household.name(), invitation.wholeHousehold() || before != null && before.wholeHousehold(), seen,
                invitation.realEstate() || before != null && before.realEstate(), invitation.manager() || before != null && before.manager());
        accounts.setAccess(userId, household.id(), access);
        audit(invitation.createdBy(), "ACCESS", household.id(), userId, name + " : accès ouvert par invitation (" + AudienceText.summary(access, directory.members(household.id()))
                + (access.manager() ? ", gestionnaire" : "") + ").");
        return household.id();
    }

    /** Nouveau foyer avec un membre au nom de la personne, qui en est gestionnaire et voit tout. */
    private Household newHousehold(long userId, String name, String householdName) {
        Household household = accounts.createHousehold(householdName, LocalDate.now(clock));
        directory.created(household);
        long member = directory.addMember(household.id(), name, false);
        accounts.setAccess(userId, household.id(), new Access(userId, household.id(), household.name(), true, Set.of(member), true, true));
        return household;
    }

    /** Compte cree par la personne : identifiant, e-mail (exige et confirme si l'administrateur l'a regle), mot de passe choisi. */
    private long createAccount(Request request, FormData form, String name) {
        String login = login(form.raw("login"));
        if (!form.raw("password").equals(form.raw("confirm"))) throw new ValidationException("La confirmation ne correspond pas au mot de passe.");
        String email = Mails.address(form.raw("email"));
        AccountRepository.Mail mail = accounts.mail();
        boolean confirm = mail.required() && mail.active();
        if (confirm && email.isEmpty()) throw new ValidationException("Indiquez votre e-mail : un lien de confirmation vous sera envoyé.");
        long id = accounts.registerUser(login, name, form.raw("password"), LocalDate.now(clock), email.isEmpty() ? null : email, confirm);
        if (confirm) {
            String token = accounts.createToken(id, "verify", email, LocalDateTime.now(clock).plusHours(24));
            Mails.send(mail, email, "Patria : confirmez votre e-mail", "Bonjour " + name + ",\n\nPour confirmer votre e-mail, ouvrez ce lien (valable 24 heures) :\n"
                    + Mails.link(mail, "/compte/email/confirmer?jeton=" + token));
        }
        return id;
    }

    /** Ouvre la session du compte qui vient d'etre cree, sur ce foyer (la confirmation de l'e-mail passe avant, si elle est exigee). */
    private Response signIn(Request request, long userId, long household) {
        Session session = sessions.create(userId, household);
        String cookie = Sessions.COOKIE + "=" + session.id() + "; Path=/; Max-Age=" + Sessions.LIFETIME.toSeconds() + "; HttpOnly; SameSite=Lax"
                + (request.isHttps() ? "; Secure" : "");
        log.recordLogin(LocalDateTime.now(clock), accounts.user(userId).orElseThrow().login(), userId, true, request.clientAddress());
        // La double authentification est proposee d'abord (« Plus tard » mene a l'accueil).
        return new Response.Redirect("/compte/securiser", List.of(cookie));
    }

    /** Cinq comptes au plus par heure et par adresse (robots). */
    private void limit(Request request) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<LocalDateTime> sent = recent.compute(request.clientAddress(), (address, times) -> {
            List<LocalDateTime> kept = new java.util.ArrayList<>(times == null ? List.of() : times);
            kept.removeIf(time -> time.isBefore(now.minusHours(1)));
            return kept;
        });
        if (sent.size() >= PER_ADDRESS) throw new ValidationException("Trop de comptes créés depuis cette adresse : réessayez dans une heure.");
        sent.add(now);
        if (recent.size() > 10_000) recent.clear();
    }

    // ---- Pages ----

    private Html signupPage(Map<String, String> values, Notice notice) {
        String mode = accounts.signupMode();
        Html body = switch (mode) {
            case "open" -> Html.concat(Html.raw("<p class=\"muted\">Votre compte et votre foyer, dont vous serez gestionnaire : vous y inviterez ensuite les autres "
                    + "membres de la famille et choisirez ce que chacun voit.</p>"), accountForm("/inscription", null, values, true));
            case "closed" -> Html.raw("<p>Les inscriptions sont fermées sur ce Patria.</p>");
            default -> Html.raw("<p>Ce Patria fonctionne <b>sur invitation</b>. Demandez un lien au gestionnaire de votre foyer ; pour créer un nouveau foyer, "
                    + "demandez-le à l'administrateur.</p>");
        };
        return Layout.document("Créer un compte · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1 class="login-title">Créer un compte</h1>%s%s
                <p class="muted"><a href="/connexion">Retour à la connexion</a></p></main>""", Layout.brand(), Components.notice(notice), body));
    }

    private Html invitationPage(Request request, String token, Map<String, String> values, Notice notice) {
        java.util.Optional<AccountRepository.Invitation> found = accounts.invitation(token, LocalDateTime.now(clock));
        if (found.isEmpty()) {
            return Layout.message("Invitation expirée", "Ce lien est inconnu, déjà utilisé ou expiré (7 jours). Demandez-en un nouveau.", request.csrf());
        }
        AccountRepository.Invitation invitation = found.get();
        String target = invitation.householdId() == null ? null : accounts.household(invitation.householdId()).map(Household::name).orElse(null);
        Html intro = Html.of("<p>%s</p>", target == null ? "Vous êtes invité à créer votre foyer sur ce Patria : vous en serez le gestionnaire."
                : "Vous êtes invité à rejoindre le foyer « " + target + " »" + (invitation.manager() ? ", en tant que gestionnaire" : "") + ".");
        Html body;
        if (request.viewer() != null && !request.viewer().isAdministrator()) {
            body = Html.of("""
                    <form method="post" action="/invitation/rejoindre" class="stack">%s<input type="hidden" name="jeton" value="%s">%s
                    <button type="submit">%s avec mon compte (%s)</button></form>""", Components.csrfField(request.csrf()), token,
                    target == null ? Html.raw("<label>Nom du foyer<input name=\"foyer\" maxlength=\"60\"></label>") : Html.EMPTY,
                    target == null ? "Créer ce foyer" : "Rejoindre", request.viewer().name());
        } else {
            body = Html.concat(accountForm("/invitation", token, values, target == null),
                    Html.of("<p class=\"muted\">Vous avez déjà un compte ? <a href=\"/connexion?suite=%s\">Connectez-vous</a> pour accepter l'invitation.</p>",
                            fr.patria.web.LocalPath.encode("/invitation?jeton=" + token)));
        }
        return Layout.document("Invitation · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1 class="login-title">Invitation</h1>%s%s%s</main>""", Layout.brand(), Components.notice(notice), intro, body));
    }

    /** Formulaire de creation de compte ; {@code household} : demande aussi le nom du nouveau foyer. */
    private Html accountForm(String action, String token, Map<String, String> values, boolean household) {
        AccountRepository.Mail mail = accounts.mail();
        boolean emailRequired = mail.required() && mail.active();
        return Html.of("""
                <form method="post" action="%s" class="stack">%s
                <label>Votre prénom<input name="name" maxlength="60" required autocomplete="given-name" value="%s"></label>
                <label>Identifiant de connexion<input name="login" maxlength="40" required autocapitalize="none" spellcheck="false" autocomplete="username" value="%s"></label>
                <label>E-mail%s<input name="email" type="email" maxlength="254"%s autocomplete="email" value="%s"></label>
                <label>Mot de passe<input name="password" type="password" minlength="%s" required autocomplete="new-password"></label>
                <label>Confirmation<input name="confirm" type="password" required autocomplete="new-password"></label>%s
                <label class="honeypot" aria-hidden="true">Site<input name="site" tabindex="-1" autocomplete="off"></label>
                <button type="submit">Créer mon compte</button></form>""",
                action, token == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"jeton\" value=\"%s\">", token),
                values.getOrDefault("name", ""), values.getOrDefault("login", ""),
                emailRequired ? " (un lien de confirmation vous sera envoyé)" : " (facultatif, pour retrouver votre mot de passe)", Html.raw(emailRequired ? " required" : ""),
                values.getOrDefault("email", ""), AccountRepository.MIN_PASSWORD,
                household ? Html.of("<label>Nom de votre foyer<input name=\"foyer\" maxlength=\"60\" placeholder=\"Foyer Martin\" value=\"%s\"></label>",
                        values.getOrDefault("foyer", "")) : Html.EMPTY);
    }
}
