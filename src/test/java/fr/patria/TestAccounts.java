package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comptes pour les tests HTTP : l'administrateur ne voit pas les finances, les donnees se saisissent avec un compte
 * gestionnaire. {@link #account} cree le compte, lui ouvre ses droits et change son mot de passe provisoire.
 */
final class TestAccounts {
    /** Une personne connectee : son cookie de session et son jeton CSRF. */
    record Visitor(String cookie, String csrf, long id) {
    }

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    private TestAccounts() {
    }

    static HttpResponse<String> get(HttpClient client, String base, Visitor visitor, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient client, String base, Visitor visitor, String path, String form) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        String body = visitor == null ? form : "csrf=" + visitor.csrf() + (form.isEmpty() ? "" : "&" + form);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final Pattern LINK = Pattern.compile("invitation\\?jeton=([A-Za-z0-9_-]+)");
    private static final Pattern BOX = Pattern.compile("name=\"(u\\d+_[a-z]+(?:_\\d+)?)\" value=\"on\"[^>]*?( checked)?>");
    /** Gestionnaire de chaque foyer, par installation de test ({@code base}). */
    private static final java.util.Map<String, Visitor> MANAGERS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ajoute un membre au foyer {@code household} par son gestionnaire, puis, si {@code visitor} est donne, lui coche
     * tout ce foyer dans le tableau des droits. Renvoie la reponse de l'ajout.
     */
    static HttpResponse<String> member(HttpClient client, String base, Visitor visitor, long household, String form) throws Exception {
        Visitor manager = managerOf(client, base, household);
        HttpResponse<String> added = post(client, base, manager, "/foyer/membres", form.contains("kind=") ? form : form + "&kind=PERSON");
        if (visitor != null && visitor.id() != manager.id()) {
            java.util.Set<String> checked = checkedRights(client, base, manager);
            for (String name : allBoxes(client, base, manager)) if (name.startsWith("u" + visitor.id() + "_") && !name.endsWith("_manager")) checked.add(name);
            assertEquals(303, post(client, base, manager, "/foyer/droits", String.join("&", checked.stream().map(name -> name + "=on").toList())).statusCode());
        }
        return added;
    }

    /** Cases cochees du tableau des droits du foyer affiche par ce gestionnaire. */
    static java.util.Set<String> checkedRights(HttpClient client, String base, Visitor manager) throws Exception {
        java.util.Set<String> checked = new java.util.LinkedHashSet<>();
        Matcher box = BOX.matcher(get(client, base, manager, "/foyer").body());
        while (box.find()) if (box.group(2) != null) checked.add(box.group(1));
        return checked;
    }

    private static java.util.Set<String> allBoxes(HttpClient client, String base, Visitor manager) throws Exception {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        Matcher box = BOX.matcher(get(client, base, manager, "/foyer").body());
        while (box.find()) all.add(box.group(1));
        return all;
    }

    /** Gestionnaire du foyer {@code household} : « gestion » pour le foyer 1, cree au besoin par un lien de gestion de l'administrateur. */
    static Visitor managerOf(HttpClient client, String base, long household) throws Exception {
        Visitor known = MANAGERS.get(base + "#" + household);
        // Un port peut resservir d'un test a l'autre : la session gardee doit encore ouvrir ce foyer.
        if (known != null && get(client, base, known, "/foyer").statusCode() == 200) return known;
        String login = household == 1 ? "gestion" : "gestion" + household;
        String page = post(client, base, admin(client, base), "/administration/foyers/" + household + "/lien", "").body();
        Visitor manager = join(client, base, page, login, household == 1 ? "Gestion" : "Gestion " + household, "");
        MANAGERS.put(base + "#" + household, manager);
        return manager;
    }

    /** Cree le compte {@code login} avec le lien d'invitation trouve dans {@code page} ; mot de passe {@code login + "-motdepasse"}. */
    static Visitor join(HttpClient client, String base, String page, String login, String name, String householdName) throws Exception {
        Matcher link = LINK.matcher(page);
        assertTrue(link.find(), page);
        String password = login + "-motdepasse";
        HttpResponse<String> created = post(client, base, null, "/invitation", "jeton=" + link.group(1) + "&name=" + name.replace(" ", "+") + "&login=" + login
                + "&password=" + password + "&confirm=" + password + (householdName.isEmpty() ? "" : "&foyer=" + householdName.replace(" ", "+")));
        assertEquals(303, created.statusCode(), created.body());
        Visitor visitor = login(client, base, login, password);
        return new Visitor(visitor.cookie(), visitor.csrf(), userId(client, base, login));
    }

    /** Identifiant d'un compte, lu dans la liste des utilisateurs de l'administrateur. */
    static long userId(HttpClient client, String base, String login) throws Exception {
        Matcher row = Pattern.compile("/administration/utilisateur/(\\d+)\"><b>[^<]*</b></a><small>" + Pattern.quote(login) + "(?: ·|<)")
                .matcher(get(client, base, admin(client, base), "/administration/utilisateurs?q=" + login).body());
        assertTrue(row.find(), "compte " + login);
        return Long.parseLong(row.group(1));
    }

    /** Connexion ; un mot de passe provisoire est remplace par {@code password + "-final"}... sauf si {@code keep}. */
    static Visitor login(HttpClient client, String base, String login, String password) throws Exception {
        HttpResponse<String> logged = post(client, base, null, "/connexion", "login=" + login + "&password=" + password);
        assertEquals(303, logged.statusCode(), "connexion de " + login);
        String cookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        String location = logged.headers().firstValue("Location").orElse("");
        String page = get(client, base, new Visitor(cookie, "", 0), location.equals("/compte/nouveau-mot-de-passe") ? location : "/compte").body();
        Matcher csrf = CSRF.matcher(page);
        assertTrue(csrf.find(), page);
        return new Visitor(cookie, csrf.group(1), 0);
    }

    /** Envoi d'un fichier (formulaire multipart) : {@code fields} en plus du jeton CSRF, puis le fichier {@code file}. */
    static HttpResponse<String> upload(HttpClient client, String base, Visitor visitor, String path, java.util.Map<String, String> fields, String file,
                                       String filename, String content) throws Exception {
        String boundary = "patriaTestBoundary";
        StringBuilder body = new StringBuilder("--" + boundary + "\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n" + visitor.csrf() + "\r\n");
        fields.forEach((name, value) -> body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(file).append("\"; filename=\"").append(filename)
                .append("\"\r\nContent-Type: text/csv\r\n\r\n").append(content).append("\r\n--").append(boundary).append("--\r\n");
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", visitor.cookie())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), java.nio.charset.StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** En-tete Location d'une redirection, vide sinon. */
    static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    static Visitor admin(HttpClient client, String base) throws Exception {
        return login(client, base, "admin", "secret");
    }

    /**
     * Cree le compte {@code login} (nom {@code name}) par invitation du gestionnaire du foyer {@code household}, puis
     * lui donne ces droits ({@code access} : {@code access=on} et {@code all}, {@code whole}, {@code estate},
     * {@code member_<id>}) ; mot de passe {@code login + "-motdepasse"}.
     */
    static Visitor account(HttpClient client, String base, Visitor admin, String login, String name, long household, String access) throws Exception {
        return account(client, base, admin, login, name, "", household, access);
    }

    /** Comme {@link #account(HttpClient, String, Visitor, String, String, long, String)} ; {@code extra} {@code membre=<foyer>:<membre|nouveau>} : le membre qu'il est. */
    static Visitor account(HttpClient client, String base, Visitor admin, String login, String name, String extra, long household, String access) throws Exception {
        Visitor manager = managerOf(client, base, household);
        // « gestion » est le gestionnaire cree avec le foyer : il voit et gere deja tout.
        if (login.equals(household == 1 ? "gestion" : "gestion" + household)) return manager;
        String member = "aucun";
        Matcher chosen = Pattern.compile("membre=\\d+(?:%3A|:)(\\w+)").matcher(extra);
        if (chosen.find()) member = chosen.group(1);
        // Invite avec les totaux (pour qu'il ait acces au foyer), puis regle ses cases exactes.
        String page = post(client, base, manager, "/foyer/invitations", "membre=" + member + "&name=" + name.replace(" ", "+") + "&whole=on").body();
        Visitor visitor = join(client, base, page, login, name, "");
        if (access != null || member.equals("aucun") || member.matches("\\d+|nouveau")) {
            java.util.Set<String> checked = checkedRights(client, base, manager);
            String prefix = "u" + visitor.id() + "_";
            java.util.Set<String> previous = new java.util.LinkedHashSet<>(checked);
            checked.removeIf(box -> box.startsWith(prefix) && !box.endsWith("_manager"));
            java.util.List<String> parameters = access == null ? java.util.List.of() : java.util.Arrays.asList(access.split("&"));
            for (String box : allBoxes(client, base, manager)) {
                if (!box.startsWith(prefix) || box.endsWith("_manager")) continue;
                String right = box.substring(prefix.length());
                // Le membre qu'il est reste coche ; le reste suit {@code access}.
                boolean own = right.startsWith("member_") && (right.equals("member_" + member) || member.equals("nouveau") && previous.contains(box));
                if (own || parameters.contains("access=on") && (parameters.contains("all=on") || parameters.contains(right + "=on"))) checked.add(box);
            }
            assertEquals(303, post(client, base, manager, "/foyer/droits", String.join("&", checked.stream().map(box -> box + "=on").toList())).statusCode());
            Visitor fresh = login(client, base, login, login + "-motdepasse");
            visitor = new Visitor(fresh.cookie(), fresh.csrf(), visitor.id());
        }
        return visitor;
    }

    /** Gestionnaire du foyer principal : il gere le foyer et voit tout. */
    static Visitor manager(HttpClient client, String base) throws Exception {
        return managerOf(client, base, 1);
    }
}
