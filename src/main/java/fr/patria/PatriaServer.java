package fr.patria;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mindrot.jbcrypt.BCrypt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PatriaServer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final int MAX_UPLOAD_BYTES = 1024 * 1024;
    private static final long SESSION_LIFETIME_MS = TimeUnit.HOURS.toMillis(12);
    private final Database database;
    private final String password;
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final long LOGIN_WINDOW_MS = TimeUnit.MINUTES.toMillis(15);
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();

    private PatriaServer(Database database, String password) {
        this.database = database;
        this.password = password;
    }

    public static void main(String[] args) throws IOException {
        String password = System.getenv("APP_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("APP_PASSWORD doit être défini.");
        }
        Path databasePath = Path.of(System.getenv().getOrDefault("DATABASE_PATH", "/data/immobilier.sqlite3"));
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Database database = new Database(databasePath);
        database.initialize();

        int port = Integer.parseInt(System.getenv().getOrDefault("HTTP_PORT", "8080"));
        PatriaServer application = new PatriaServer(database, password);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", application::handle);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }));
        server.start();
        System.out.println("Patria listens on port " + port + ".");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/css/patria.css" -> resource(exchange, "static/css/patria.css", "text/css; charset=utf-8");
                case "/js/app.js" -> resource(exchange, "static/js/app.js", "text/javascript; charset=utf-8");
                case "/images/patria-logo.png" -> resource(exchange, "static/images/patria-logo.png", "image/png");
                default -> route(exchange, path);
            }
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.message("Demande invalide", exception.getMessage(), csrfOf(exchange)));
        } catch (PayloadTooLargeException exception) {
            page(exchange, 413, Pages.message("Demande trop volumineuse", "Le formulaire ou le fichier dépasse la taille autorisée.", csrfOf(exchange)));
        } catch (Exception exception) {
            exception.printStackTrace();
            page(exchange, 500, Pages.message("Erreur interne", "Une erreur est survenue.", csrfOf(exchange)));
        } finally {
            exchange.close();
        }
    }

    private String csrfOf(HttpExchange exchange) {
        Session session = session(exchange);
        return session == null ? null : session.csrf();
    }

    private static final Map<String, String> SUCCESS = Map.of(
            "cree", "Bien créé.", "enregistre", "Modifications enregistrées.", "supprime", "Suppression effectuée.",
            "echeancier", "Échéancier importé.", "apport", "Apport enregistré.", "renomme", "Prêt renommé.");

    private void route(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        boolean write = method.equals("POST");
        if (path.equals("/connexion")) {
            if (read) loginPage(exchange); else if (write) login(exchange); else methodNotAllowed(exchange);
            return;
        }
        if (path.equals("/deconnexion") && write) {
            logout(exchange);
            return;
        }
        Session session = session(exchange);
        if (session == null) {
            redirect(exchange, "/connexion");
            return;
        }
        if (path.matches(".*\\d{19,}.*")) {
            page(exchange, 404, Pages.message("Page introuvable", "Cette page n'existe pas.", session.csrf()));
            return;
        }
        String[] parts = path.substring(1).split("/");
        Long id = parts.length > 1 && parts[1].matches("\\d{1,18}") ? Long.valueOf(parts[1])
                : parts.length > 2 && parts[2].matches("\\d{1,18}") ? Long.valueOf(parts[2]) : null;
        String ok = queryValue(exchange, "ok");
        Pages.Notice success = ok == null ? null : Pages.Notice.success(SUCCESS.get(ok));
        String csrf = session.csrf();

        if (read && path.equals("/")) {
            page(exchange, 200, Pages.dashboard(database.properties(), database.loans(), database.schedules(), database.allContributions(), csrf));
        } else if (read && path.equals("/administration")) {
            page(exchange, 200, Pages.administration(database.properties(), database.loans(), database.schedules(), success, csrf));
        } else if (read && path.equals("/administration/bien/nouveau")) {
            Map<String, String> defaults = new HashMap<>(Map.of("kind", "Appartement", "purchase_date", LocalDate.now().toString(), "sale_fees_pct", "8"));
            page(exchange, 200, Pages.propertyForm(null, defaults, null, csrf));
        } else if (write && path.equals("/bien")) {
            addProperty(exchange, session);
        } else if (read && path.matches("/bien/\\d+")) {
            withProperty(exchange, id, property -> page(exchange, 200, Pages.propertyDetail(property, database.loansForProperty(id), database.schedules(), database.contributions(id), success, csrf)));
        } else if (read && path.matches("/bien/\\d+/mensualites")) {
            withProperty(exchange, id, property -> page(exchange, 200, Pages.monthlyPayments(property, database.loansForProperty(id), database.schedules(), csrf)));
        } else if (read && path.matches("/administration/bien/\\d+")) {
            withProperty(exchange, id, property -> page(exchange, 200, Pages.propertyForm(property, Pages.propertyValues(property), success, csrf)));
        } else if (read && path.matches("/administration/bien/\\d+/financements")) {
            withProperty(exchange, id, property -> financingPage(exchange, property, success, Map.of(), csrf, 200));
        } else if (write && path.matches("/administration/bien/\\d+/proprietes")) {
            updateProperty(exchange, session, id);
        } else if (write && path.matches("/administration/bien/\\d+/supprimer")) {
            checkedForm(exchange, session);
            withProperty(exchange, id, property -> { database.deleteProperty(id); redirect(exchange, "/administration?ok=supprime"); });
        } else if (write && path.matches("/administration/bien/\\d+/apports")) {
            addContribution(exchange, session, id);
        } else if (read && path.matches("/apport/\\d+")) {
            redirect(exchange, "/apport/" + id + "/gerer");
        } else if (read && path.matches("/apport/\\d+/gerer")) {
            withContribution(exchange, id, contribution -> page(exchange, 200, Pages.contributionManagement(contribution, database.property(contribution.propertyId()), Pages.contributionValues(contribution), null, csrf)));
        } else if (write && path.matches("/apport/\\d+/gerer")) {
            updateContribution(exchange, session, id);
        } else if (write && path.matches("/apport/\\d+/supprimer")) {
            checkedForm(exchange, session);
            withContribution(exchange, id, contribution -> { database.deleteContribution(id); redirect(exchange, "/bien/" + contribution.propertyId() + "?ok=supprime"); });
        } else if (write && path.equals("/pret")) {
            addLoan(exchange, session);
        } else if (read && path.matches("/pret/\\d+")) {
            withLoan(exchange, id, loan -> page(exchange, 200, Pages.loanDetail(loan, database.scheduleLines(id), success, csrf)));
        } else if (read && path.matches("/pret/\\d+/echeancier")) {
            withLoan(exchange, id, loan -> page(exchange, 200, Pages.loanManagement(loan, success, csrf)));
        } else if (write && path.matches("/pret/\\d+/echeancier")) {
            withLoan(exchange, id, loan -> importSchedule(exchange, session, loan));
        } else if (write && path.matches("/pret/\\d+/renommer")) {
            withLoan(exchange, id, loan -> renameLoan(exchange, session, loan));
        } else if (write && path.matches("/pret/\\d+/supprimer")) {
            checkedForm(exchange, session);
            withLoan(exchange, id, loan -> { database.deleteLoan(id); redirect(exchange, "/bien/" + loan.propertyId() + "?ok=supprime"); });
        } else {
            page(exchange, 404, Pages.message("Page introuvable", "Cette page n'existe pas.", csrf));
        }
    }

    @FunctionalInterface
    private interface Action<T> {
        void run(T value) throws IOException;
    }

    private void withProperty(HttpExchange exchange, long id, Action<Property> action) throws IOException {
        Property property = database.property(id);
        if (property == null) page(exchange, 404, Pages.message("Bien introuvable", "Ce bien n'existe pas.", csrfOf(exchange)));
        else action.run(property);
    }

    private void withLoan(HttpExchange exchange, long id, Action<Loan> action) throws IOException {
        Loan loan = database.loan(id);
        if (loan == null) page(exchange, 404, Pages.message("Prêt introuvable", "Ce prêt n'existe pas.", csrfOf(exchange)));
        else action.run(loan);
    }

    private void withContribution(HttpExchange exchange, long id, Action<Contribution> action) throws IOException {
        Contribution contribution = database.contribution(id);
        if (contribution == null) page(exchange, 404, Pages.message("Apport introuvable", "Cet apport n'existe pas.", csrfOf(exchange)));
        else action.run(contribution);
    }

    private void financingPage(HttpExchange exchange, Property property, Pages.Notice notice, Map<String, String> values, String csrf, int status) throws IOException {
        page(exchange, status, Pages.financing(property, database.loansForProperty(property.id()), database.schedules(), database.contributions(property.id()), notice, values, csrf));
    }

    private void loginPage(HttpExchange exchange) throws IOException {
        if (session(exchange) != null) {
            redirect(exchange, "/");
        } else {
            String query = exchange.getRequestURI().getQuery();
            page(exchange, 200, Pages.login(query != null && query.contains("error"), query != null && query.contains("logout"), query != null && query.contains("throttled")));
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        // Derriere le reverse proxy, la derniere entree de X-Forwarded-For est celle ajoutee par le proxy (non falsifiable).
        java.net.InetAddress remote = exchange.getRemoteAddress().getAddress();
        String client = remote.getHostAddress();
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if ((remote.isLoopbackAddress() || remote.isSiteLocalAddress()) && forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            client = hops[hops.length - 1].trim();
        }
        long now = System.currentTimeMillis();
        Attempts attempts = failures.compute(client, (key, current) -> current == null || current.since() + LOGIN_WINDOW_MS < now ? new Attempts(0, now) : current);
        if (attempts.count() >= MAX_LOGIN_FAILURES) {
            redirect(exchange, "/connexion?throttled");
            return;
        }
        String entered = form(exchange).getOrDefault("password", "");
        if (!passwordMatches(entered)) {
            failures.merge(client, new Attempts(1, now), (current, ignored) -> new Attempts(current.count() + 1, current.since()));
            redirect(exchange, "/connexion?error");
            return;
        }
        failures.remove(client);
        sessions.values().removeIf(session -> session.expiresAt() < now);
        String id = token();
        sessions.put(id, new Session(token(), now + SESSION_LIFETIME_MS));
        String cookie = "patria_session=" + id + "; Path=/; Max-Age=" + SESSION_LIFETIME_MS / 1000 + "; HttpOnly; SameSite=Lax";
        if ("https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"))) cookie += "; Secure";
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        redirect(exchange, "/");
    }

    private void logout(HttpExchange exchange) throws IOException {
        Session session = session(exchange);
        if (session != null) {
            String csrf = form(exchange).getOrDefault("csrf", "");
            if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8))) {
                page(exchange, 400, Pages.message("Demande invalide", "Formulaire expiré. Rechargez la page.", session.csrf()));
                return;
            }
            sessions.remove(cookie(exchange, "patria_session"));
        }
        exchange.getResponseHeaders().add("Set-Cookie", "patria_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirect(exchange, "/connexion?logout");
    }

    private Property propertyFromForm(Map<String, String> form, long id, Property current) {
        String kind = required(form, "kind", "Type");
        if (!kind.equals("Appartement") && !kind.equals("Maison")) throw new IllegalArgumentException("Type de bien invalide.");
        LocalDate purchaseDate = optionalPastDate(form, "purchase_date", "Date d'acquisition", current == null ? LocalDate.now() : current.purchaseDate());
        LocalDate estimateDate = optionalPastDate(form, "estimate_date", "Date d'estimation", purchaseDate);
        if (estimateDate.isBefore(purchaseDate)) throw new IllegalArgumentException("La date d'estimation ne peut pas précéder l'acquisition.");
        BigDecimal estimateLow = optionalAmount(form, "estimate", "Estimation prudente");
        BigDecimal estimateHigh = optionalAmount(form, "estimate_high", "Estimation optimiste");
        if (estimateHigh.signum() == 0) estimateHigh = estimateLow;
        if (estimateHigh.compareTo(estimateLow) < 0) throw new IllegalArgumentException("L'estimation optimiste doit être supérieure ou égale à l'estimation prudente.");
        BigDecimal saleFees = optionalPercent(form, "sale_fees_pct", "Frais de vente et fiscalité", Valuation.DEFAULT_SALE_FEES);
        return new Property(id, required(form, "name", "Nom du bien"), kind, optionalText(form, "commune", 100, "Commune"), optionalText(form, "address", 200, "Adresse"),
                current == null ? "" : current.insee(), optionalAmount(form, "area", "Surface"), optionalAmount(form, "purchase", "Coût d'acquisition"), purchaseDate,
                estimateLow, estimateHigh, estimateDate, saleFees, optionalAmount(form, "recurring_fees_annual", "Frais récurrents annuels"));
    }

    private void addProperty(HttpExchange exchange, Session session) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        try {
            long id = database.addProperty(propertyFromForm(form, 0, null));
            redirect(exchange, "/bien/" + id + "?ok=cree");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.propertyForm(null, form, Pages.Notice.error(exception.getMessage()), session.csrf()));
        }
    }

    private void updateProperty(HttpExchange exchange, Session session, long id) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        withProperty(exchange, id, current -> {
            try {
                database.updateProperty(propertyFromForm(form, id, current));
                redirect(exchange, "/bien/" + id + "?ok=enregistre");
            } catch (IllegalArgumentException exception) {
                page(exchange, 400, Pages.propertyForm(current, form, Pages.Notice.error(exception.getMessage()), session.csrf()));
            }
        });
    }

    private void addLoan(HttpExchange exchange, Session session) throws IOException {
        MultipartForm form = multipartForm(exchange);
        checkCsrf(session, form.value("csrf"));
        long propertyId;
        try {
            propertyId = Long.parseLong(form.value("property_id"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Bien invalide.");
        }
        withProperty(exchange, propertyId, property -> {
            try {
                String name = required(form.values(), "loan_name", "Nom du prêt");
                List<ScheduleLine> lines = CsvScheduleParser.parse(csv(form));
                long loanId = database.createLoan(LoanMath.derive(0, propertyId, name, lines), lines);
                redirect(exchange, "/pret/" + loanId + "?ok=echeancier");
            } catch (IllegalArgumentException exception) {
                financingPage(exchange, property, Pages.Notice.error(exception.getMessage()), form.values(), session.csrf(), 400);
            }
        });
    }

    private void importSchedule(HttpExchange exchange, Session session, Loan loan) throws IOException {
        MultipartForm form = multipartForm(exchange);
        checkCsrf(session, form.value("csrf"));
        try {
            List<ScheduleLine> lines = CsvScheduleParser.parse(csv(form));
            database.replaceSchedule(LoanMath.derive(loan.id(), loan.propertyId(), loan.name(), lines), lines);
            redirect(exchange, "/pret/" + loan.id() + "?ok=echeancier");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.loanManagement(loan, Pages.Notice.error(exception.getMessage()), session.csrf()));
        }
    }

    private void renameLoan(HttpExchange exchange, Session session, Loan loan) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        try {
            database.renameLoan(loan.id(), required(form, "name", "Nom du prêt"));
            redirect(exchange, "/pret/" + loan.id() + "?ok=renomme");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.loanManagement(loan, Pages.Notice.error(exception.getMessage()), session.csrf()));
        }
    }

    private static byte[] csv(MultipartForm form) {
        byte[] csv = form.file("csv");
        if (csv == null || csv.length == 0) throw new IllegalArgumentException("Fichier CSV manquant.");
        return csv;
    }

    private Contribution contributionFromForm(Map<String, String> form, long id, long propertyId) {
        return new Contribution(id, propertyId, required(form, "name", "Libellé"), positive(form, "amount", "Montant"), date(form.getOrDefault("date", ""), "Date"));
    }

    private void addContribution(HttpExchange exchange, Session session, long propertyId) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        withProperty(exchange, propertyId, property -> {
            try {
                database.addContribution(contributionFromForm(form, 0, propertyId));
                redirect(exchange, "/bien/" + propertyId + "?ok=apport");
            } catch (IllegalArgumentException exception) {
                financingPage(exchange, property, Pages.Notice.error(exception.getMessage()), form, session.csrf(), 400);
            }
        });
    }

    private void updateContribution(HttpExchange exchange, Session session, long contributionId) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        withContribution(exchange, contributionId, current -> {
            try {
                database.updateContribution(contributionFromForm(form, current.id(), current.propertyId()));
                redirect(exchange, "/bien/" + current.propertyId() + "?ok=apport");
            } catch (IllegalArgumentException exception) {
                page(exchange, 400, Pages.contributionManagement(current, database.property(current.propertyId()), form, Pages.Notice.error(exception.getMessage()), session.csrf()));
            }
        });
    }

    private Map<String, String> checkedForm(HttpExchange exchange, Session session) throws IOException {
        Map<String, String> form = form(exchange);
        checkCsrf(session, form.getOrDefault("csrf", ""));
        return form;
    }

    private static void checkCsrf(Session session, String csrf) {
        if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Formulaire expiré. Rechargez la page.");
        }
    }

    private static String queryValue(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] entry = part.split("=", 2);
            if (entry[0].equals(key)) return entry.length == 2 ? entry[1] : "";
        }
        return null;
    }

    private Session session(HttpExchange exchange) {
        String id = cookie(exchange, "patria_session");
        if (id == null) return null;
        Session session = sessions.get(id);
        if (session == null || session.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(id);
            return null;
        }
        return session;
    }

    private static String cookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] entry = part.trim().split("=", 2);
            if (entry.length == 2 && entry[0].equals(name)) return entry[1];
        }
        return null;
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        byte[] body = requestBody(exchange, MAX_FORM_BYTES);
        String bodyText = new String(body, StandardCharsets.UTF_8);
        Map<String, String> values = new ConcurrentHashMap<>();
        for (String item : bodyText.split("&")) {
            String[] entry = item.split("=", 2);
            if (entry.length == 2) values.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8), URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static MultipartForm multipartForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) throw new IllegalArgumentException("Format d'envoi invalide.");
        String marker = "boundary=";
        int boundaryAt = contentType.indexOf(marker);
        if (boundaryAt < 0) throw new IllegalArgumentException("Frontiere multipart manquante.");
        String boundary = contentType.substring(boundaryAt + marker.length()).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 2) boundary = boundary.substring(1, boundary.length() - 1);
        if (boundary.isBlank() || boundary.length() > 70 || !boundary.matches("[0-9A-Za-z'()+_,./:=?-]+")) throw new IllegalArgumentException("Frontiere multipart invalide.");
        byte[] body = requestBody(exchange, MAX_UPLOAD_BYTES);
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] first = ("--" + boundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        if (!startsWith(body, first)) throw new IllegalArgumentException("Corps multipart invalide.");
        Map<String, String> values = new ConcurrentHashMap<>();
        Map<String, byte[]> files = new ConcurrentHashMap<>();
        int start = first.length;
        while (start < body.length) {
            int headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start);
            if (headersEnd < 0) throw new IllegalArgumentException("Partie multipart invalide.");
            String headers = new String(body, start, headersEnd - start, StandardCharsets.ISO_8859_1);
            String disposition = Arrays.stream(headers.split("\r\n")).filter(header -> header.toLowerCase(java.util.Locale.ROOT).startsWith("content-disposition:")).findFirst().orElse("");
            String name = dispositionValue(disposition, "name");
            if (name == null || name.length() > 32) throw new IllegalArgumentException("Partie multipart invalide.");
            int contentStart = headersEnd + 4;
            int next = indexOf(body, delimiter, contentStart);
            if (next < 0) throw new IllegalArgumentException("Fin multipart manquante.");
            byte[] content = Arrays.copyOfRange(body, contentStart, next);
            if (dispositionValue(disposition, "filename") == null) values.put(name, new String(content, StandardCharsets.UTF_8)); else files.put(name, content);
            start = next + delimiter.length;
            if (startsWith(body, "--".getBytes(StandardCharsets.ISO_8859_1), start)) break;
            if (!startsWith(body, "\r\n".getBytes(StandardCharsets.ISO_8859_1), start)) throw new IllegalArgumentException("Separateur multipart invalide.");
            start += 2;
        }
        return new MultipartForm(values, files);
    }

    private static byte[] requestBody(HttpExchange exchange, int maximum) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        try {
            if (length != null && Long.parseLong(length) > maximum) throw new PayloadTooLargeException();
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("Content-Length invalide."); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream input = exchange.getRequestBody()) {
            for (int read; (read = input.read(buffer)) != -1;) {
                if (output.size() + read > maximum) throw new PayloadTooLargeException();
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static int indexOf(byte[] source, byte[] target, int from) { outer: for (int i = from; i <= source.length - target.length; i++) { for (int j = 0; j < target.length; j++) if (source[i + j] != target[j]) continue outer; return i; } return -1; }
    private static boolean startsWith(byte[] source, byte[] target) { return startsWith(source, target, 0); }
    private static boolean startsWith(byte[] source, byte[] target, int at) { if (at + target.length > source.length) return false; for (int i = 0; i < target.length; i++) if (source[at + i] != target[i]) return false; return true; }
    private static String dispositionValue(String disposition, String key) { String prefix = key + "=\""; int start = disposition.indexOf(prefix); if (start < 0) return null; int end = disposition.indexOf('"', start + prefix.length()); return end < 0 ? null : disposition.substring(start + prefix.length(), end); }

    private static String required(Map<String, String> form, String key, String label) {
        String value = form.getOrDefault(key, "").trim();
        if (value.isEmpty() || value.length() > 100) throw new IllegalArgumentException(label + " invalide.");
        return value;
    }

    private static String optionalText(Map<String, String> form, String key, int maximum, String label) {
        String value = form.getOrDefault(key, "").trim();
        if (value.length() > maximum) throw new IllegalArgumentException(label + " invalide.");
        return value;
    }

    private static BigDecimal amount(Map<String, String> form, String key, String label) {
        try {
            BigDecimal value = new BigDecimal(normalizeNumber(required(form, key, label)));
            if (value.signum() < 0) throw new IllegalArgumentException(label + " ne peut pas être négatif.");
            if (value.compareTo(new BigDecimal("1000000000")) > 0) throw new IllegalArgumentException(label + " est trop élevé.");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static BigDecimal positive(Map<String, String> form, String key, String label) {
        BigDecimal value = amount(form, key, label);
        if (value.signum() == 0) throw new IllegalArgumentException(label + " doit être supérieur à zéro.");
        return value;
    }

    private static BigDecimal optionalAmount(Map<String, String> form, String key, String label) {
        return form.getOrDefault(key, "").isBlank() ? BigDecimal.ZERO : amount(form, key, label);
    }

    /** Pourcentage saisi (ex. "8" ou "8,5") converti en ratio (0.08). */
    private static BigDecimal optionalPercent(Map<String, String> form, String key, String label, BigDecimal defaultValue) {
        String value = normalizeNumber(form.getOrDefault(key, "")).replace("%", "");
        if (value.isEmpty()) return defaultValue;
        try {
            BigDecimal percent = new BigDecimal(value);
            if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException(label + " doit être compris entre 0 et 100 %.");
            return percent.divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static String normalizeNumber(String value) {
        return value.trim().replace(" ", "").replace("\u202f", "").replace("\u00a0", "").replace("€", "").replace(',', '.');
    }

    private static LocalDate date(String value, String label) {
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static LocalDate pastDate(String value, String label) {
        LocalDate date = date(value, label);
        if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException(label + " ne peut pas être dans le futur.");
        return date;
    }

    private static LocalDate optionalPastDate(Map<String, String> form, String key, String label, LocalDate defaultValue) {
        String value = form.getOrDefault(key, "");
        return value.isBlank() ? defaultValue : pastDate(value, label);
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean passwordMatches(String entered) {
        if (password.startsWith("{bcrypt}")) return BCrypt.checkpw(entered, password.substring("{bcrypt}".length()));
        return MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), entered.getBytes(StandardCharsets.UTF_8));
    }

    private static void resource(HttpExchange exchange, String path, String contentType) throws IOException {
        try (InputStream input = PatriaServer.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] content = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST");
        page(exchange, 405, Pages.message("Méthode non autorisée", "Cette action n'est pas disponible.", null));
    }

    private static void page(HttpExchange exchange, int status, String html) throws IOException {
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : content.length);
        if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(content);
    }

    private record Session(String csrf, long expiresAt) {
    }

    private record Attempts(int count, long since) {
    }

    private record MultipartForm(Map<String, String> values, Map<String, byte[]> files) {
        String value(String name) { return values.getOrDefault(name, ""); }
        byte[] file(String name) { return files.get(name); }
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}
