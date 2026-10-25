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
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PatriaServer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FORM_BYTES = 64 * 1024;
    private static final long SESSION_LIFETIME_MS = TimeUnit.HOURS.toMillis(12);
    private final Database database;
    private final String password;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private PatriaServer(Database database, String password) {
        this.database = database;
        this.password = password;
    }

    public static void main(String[] args) throws IOException {
        String password = System.getenv("APP_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("APP_PASSWORD doit etre defini.");
        }
        Path databasePath = Path.of(System.getenv().getOrDefault("DATABASE_PATH", "/data/immobilier.sqlite3"));
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Database database = new Database(databasePath);
        database.initialize();

        int port = 8080;
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
            if (path.equals("/css/patria.css")) {
                resource(exchange, "static/css/patria.css", "text/css; charset=utf-8");
                return;
            }
            if (path.equals("/images/patria-logo.png")) {
                resource(exchange, "static/images/patria-logo.png", "image/png");
                return;
            }
            route(exchange, path);
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.message("Demande invalide", exception.getMessage()));
        } catch (PayloadTooLargeException exception) {
            page(exchange, 413, Pages.message("Demande trop volumineuse", "Le formulaire depasse la taille autorisee."));
        } catch (Exception exception) {
            exception.printStackTrace();
            page(exchange, 500, Pages.message("Erreur interne", "Une erreur est survenue."));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (path.equals("/connexion")) {
            if (method.equals("GET") || method.equals("HEAD")) {
                loginPage(exchange);
            } else if (method.equals("POST")) {
                login(exchange);
            } else {
                methodNotAllowed(exchange);
            }
            return;
        }
        if (path.equals("/deconnexion") && method.equals("POST")) {
            logout(exchange);
            return;
        }

        Session session = session(exchange);
        if (session == null) {
            redirect(exchange, "/connexion");
            return;
        }
        if (path.equals("/") && (method.equals("GET") || method.equals("HEAD"))) {
            page(exchange, 200, Pages.dashboard(database.properties(), database.loans(), session.csrf()));
        } else if (path.equals("/administration") && (method.equals("GET") || method.equals("HEAD"))) {
            String query = exchange.getRequestURI().getQuery();
            String success = query != null && query.contains("success=bien") ? "Bien ajoute." : query != null && query.contains("success=pret") ? "Pret ajoute." : "";
            page(exchange, 200, Pages.administration(database.properties(), database.loans(), session.csrf(), "", success));
        } else if (path.equals("/bien") && method.equals("POST")) {
            addProperty(exchange, session);
        } else if (path.equals("/pret") && method.equals("POST")) {
            addLoan(exchange, session);
        } else {
            page(exchange, 404, Pages.message("Page introuvable", "Cette page n'existe pas."));
        }
    }

    private void loginPage(HttpExchange exchange) throws IOException {
        if (session(exchange) != null) {
            redirect(exchange, "/");
        } else {
            String query = exchange.getRequestURI().getQuery();
            page(exchange, 200, Pages.login(query != null && query.contains("error"), query != null && query.contains("logout")));
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        String entered = form(exchange).getOrDefault("password", "");
        if (!passwordMatches(entered)) {
            redirect(exchange, "/connexion?error");
            return;
        }
        String id = token();
        Session session = new Session(token(), System.currentTimeMillis() + SESSION_LIFETIME_MS);
        sessions.put(id, session);
        String cookie = "patria_session=" + id + "; Path=/; HttpOnly; SameSite=Lax";
        if ("https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"))) {
            cookie += "; Secure";
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
        redirect(exchange, "/");
    }

    private void logout(HttpExchange exchange) throws IOException {
        Session session = session(exchange);
        if (session != null) {
            String csrf = form(exchange).getOrDefault("csrf", "");
            if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8))) {
                page(exchange, 400, Pages.message("Demande invalide", "Formulaire expire. Rechargez la page."));
                return;
            }
            sessions.remove(cookie(exchange, "patria_session"));
        }
        exchange.getResponseHeaders().add("Set-Cookie", "patria_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirect(exchange, "/connexion?logout");
    }

    private void addProperty(HttpExchange exchange, Session session) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        try {
            String kind = required(form, "kind", "Type");
            if (!kind.equals("Appartement") && !kind.equals("Maison")) throw new IllegalArgumentException("Type de bien invalide.");
            String insee = required(form, "insee", "Code INSEE").toUpperCase(Locale.ROOT);
            if (!insee.matches("[0-9A-Z]{5}")) throw new IllegalArgumentException("Code INSEE invalide.");
            LocalDate purchaseDate = pastDate(required(form, "purchase_date", "Date d'achat"), "Date d'achat");
            LocalDate estimateDate = pastDate(required(form, "estimate_date", "Date d'estimation"), "Date d'estimation");
            if (estimateDate.isBefore(purchaseDate)) throw new IllegalArgumentException("L'estimation ne peut pas preceder l'achat.");
            database.addProperty(new Property(0, required(form, "name", "Nom"), kind, required(form, "commune", "Commune"), insee,
                    positive(form, "area", "Surface"), amount(form, "purchase", "Prix d'achat"), purchaseDate,
                    amount(form, "estimate", "Estimation"), estimateDate));
            redirect(exchange, "/administration?success=bien");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.administration(database.properties(), database.loans(), session.csrf(), exception.getMessage(), ""));
        }
    }

    private void addLoan(HttpExchange exchange, Session session) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        try {
            long propertyId = Long.parseLong(required(form, "property_id", "Bien"));
            if (!database.propertyExists(propertyId)) throw new IllegalArgumentException("Bien introuvable.");
            BigDecimal initial = positive(form, "initial", "Capital initial");
            BigDecimal balance = amount(form, "reference_balance", "Capital restant du");
            if (balance.compareTo(initial) > 0) throw new IllegalArgumentException("Le capital restant ne peut pas depasser le capital initial.");
            LocalDate startDate = pastDate(required(form, "start_date", "Date de debut"), "Date de debut");
            LocalDate referenceDate = pastDate(required(form, "reference_date", "Date de reference"), "Date de reference");
            if (referenceDate.isBefore(startDate)) throw new IllegalArgumentException("La date de reference doit suivre le debut du pret.");
            database.addLoan(new Loan(0, propertyId, "", required(form, "name", "Nom"), initial, balance, referenceDate,
                    amount(form, "rate", "Taux"), integer(form, "duration_months", 1, 600, "Duree"), startDate,
                    integer(form, "due_day", 1, 31, "Jour d'echeance"), positive(form, "payment", "Mensualite"),
                    amount(form, "insurance", "Assurance")));
            redirect(exchange, "/administration?success=pret");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.administration(database.properties(), database.loans(), session.csrf(), exception.getMessage(), ""));
        }
    }

    private Map<String, String> checkedForm(HttpExchange exchange, Session session) throws IOException {
        Map<String, String> form = form(exchange);
        if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), form.getOrDefault("csrf", "").getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Formulaire expire. Rechargez la page.");
        }
        return form;
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
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && Long.parseLong(length) > MAX_FORM_BYTES) throw new PayloadTooLargeException();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream input = exchange.getRequestBody()) {
            for (int read; (read = input.read(buffer)) != -1;) {
                if (output.size() + read > MAX_FORM_BYTES) throw new PayloadTooLargeException();
                output.write(buffer, 0, read);
            }
        }
        String body = output.toString(StandardCharsets.UTF_8);
        Map<String, String> values = new ConcurrentHashMap<>();
        for (String item : body.split("&")) {
            String[] entry = item.split("=", 2);
            if (entry.length == 2) values.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8), URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String required(Map<String, String> form, String key, String label) {
        String value = form.getOrDefault(key, "").trim();
        if (value.isEmpty() || value.length() > 100) throw new IllegalArgumentException(label + " invalide.");
        return value;
    }

    private static BigDecimal amount(Map<String, String> form, String key, String label) {
        try {
            BigDecimal value = new BigDecimal(required(form, key, label).replace(',', '.'));
            if (value.signum() < 0) throw new IllegalArgumentException(label + " ne peut pas etre negatif.");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static BigDecimal positive(Map<String, String> form, String key, String label) {
        BigDecimal value = amount(form, key, label);
        if (value.signum() == 0) throw new IllegalArgumentException(label + " doit etre superieur a zero.");
        return value;
    }

    private static int integer(Map<String, String> form, String key, int min, int max, String label) {
        try {
            int value = Integer.parseInt(required(form, key, label));
            if (value < min || value > max) throw new IllegalArgumentException(label + " invalide.");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static LocalDate pastDate(String value, String label) {
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException(label + " ne peut pas etre dans le futur.");
            return date;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
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
        page(exchange, 405, Pages.message("Methode non autorisee", "Cette action n'est pas disponible."));
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

    private static final class PayloadTooLargeException extends IOException {
    }
}
