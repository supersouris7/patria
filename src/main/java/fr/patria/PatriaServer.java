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
            if (path.equals("/js/table-sort.js")) {
                resource(exchange, "static/js/table-sort.js", "text/javascript; charset=utf-8");
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
            page(exchange, 413, Pages.message("Demande trop volumineuse", "Le formulaire ou le fichier depasse la taille autorisee."));
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
            page(exchange, 200, Pages.dashboard(database, database.properties(), database.loans(), session.csrf()));
        } else if (path.equals("/administration") && (method.equals("GET") || method.equals("HEAD"))) {
            page(exchange, 200, Pages.settings(session.csrf()));
        } else if (path.equals("/bien") && method.equals("POST")) {
            addProperty(exchange, session);
        } else if (path.equals("/administration/bien/nouveau") && (method.equals("GET") || method.equals("HEAD"))) {
            page(exchange, 200, Pages.newProperty(session.csrf()));
        } else if (path.matches("/bien/\\d+") && (method.equals("GET") || method.equals("HEAD"))) {
            propertyPage(exchange, session, Long.parseLong(path.substring("/bien/".length())));
        } else if (path.matches("/administration/bien/\\d+") && (method.equals("GET") || method.equals("HEAD"))) {
            propertyAdministrationPage(exchange, session, Long.parseLong(path.substring("/administration/bien/".length())));
        } else if (path.matches("/administration/bien/\\d+/financements") && (method.equals("GET") || method.equals("HEAD"))) {
            financingPage(exchange, session, Long.parseLong(path.substring("/administration/bien/".length(), path.lastIndexOf('/'))));
        } else if (path.matches("/administration/bien/\\d+/proprietes") && method.equals("POST")) {
            updateProperty(exchange, session, Long.parseLong(path.substring("/administration/bien/".length(), path.lastIndexOf('/'))));
        } else if (path.equals("/pret") && method.equals("POST")) {
            addLoan(exchange, session);
        } else if (path.matches("/pret/\\d+/echeancier") && (method.equals("GET") || method.equals("HEAD"))) {
            schedulePage(exchange, session, loanId(path.substring(0, path.lastIndexOf('/'))));
        } else if (path.matches("/pret/\\d+") && (method.equals("GET") || method.equals("HEAD"))) {
            loanPage(exchange, session, loanId(path));
        } else if (path.matches("/pret/\\d+/echeancier") && method.equals("POST")) {
            importSchedule(exchange, session, loanId(path.substring(0, path.lastIndexOf('/'))));
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
            LocalDate purchaseDate = optionalPastDate(form, "purchase_date", "Date d'achat", LocalDate.now());
            LocalDate estimateDate = optionalPastDate(form, "estimate_date", "Date d'estimation", purchaseDate);
            if (estimateDate.isBefore(purchaseDate)) throw new IllegalArgumentException("L'estimation ne peut pas preceder l'achat.");
            database.addProperty(new Property(0, required(form, "name", "Nom"), kind, optionalText(form, "commune", 100, "Commune"), optionalText(form, "address", 200, "Adresse"), "",
                    optionalAmount(form, "area", "Surface"), optionalAmount(form, "purchase", "Prix d'achat"), purchaseDate,
                    optionalAmount(form, "estimate", "Estimation"), estimateDate));
            redirect(exchange, "/administration?success=bien");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.administration(database, database.properties(), database.loans(), session.csrf(), exception.getMessage(), ""));
        }
    }

    private void addLoan(HttpExchange exchange, Session session) throws IOException {
        try {
            MultipartForm form = multipartForm(exchange);
            if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), form.value("csrf").getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Formulaire expire. Rechargez la page.");
            long propertyId = Long.parseLong(required(form.values(), "property_id", "Bien"));
            if (!database.propertyExists(propertyId)) throw new IllegalArgumentException("Bien introuvable.");
            byte[] csv = form.file("csv");
            if (csv == null || csv.length == 0) throw new IllegalArgumentException("Fichier CSV manquant.");
            List<ScheduleLine> lines = CsvScheduleParser.parse(csv);
            LocalDate startDate = lines.stream().map(ScheduleLine::date).min(LocalDate::compareTo).orElseThrow();
            ScheduleLine latest = lines.stream().max(java.util.Comparator.comparing(ScheduleLine::date)).orElseThrow();
            BigDecimal initial = lines.stream().filter(line -> line.type().equals("DEBLOCAGE")).map(ScheduleLine::amount).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (initial.signum() == 0) initial = lines.stream().map(ScheduleLine::remainingPrincipal).filter(java.util.Objects::nonNull).findFirst().orElse(BigDecimal.ZERO);
            BigDecimal balance = latest.remainingPrincipal() == null ? BigDecimal.ZERO : latest.remainingPrincipal();
            BigDecimal rate = lines.stream().map(ScheduleLine::annualRate).filter(java.util.Objects::nonNull).reduce((first, second) -> second).orElse(BigDecimal.ZERO);
            BigDecimal payment = lines.stream().filter(line -> line.type().equals("ECHEANCE")).map(ScheduleLine::amount).filter(java.util.Objects::nonNull).reduce((first, second) -> second).orElse(BigDecimal.ZERO);
            int duration = Math.max(1, (int) java.time.temporal.ChronoUnit.MONTHS.between(startDate, latest.date()) + 1);
            long loanId = database.addLoan(new Loan(0, propertyId, "", required(form.values(), "name", "Nom"), initial, balance, latest.date(), rate,
                    duration, startDate, startDate.getDayOfMonth(), payment, BigDecimal.ZERO));
            database.replaceScheduleLines(loanId, lines);
            redirect(exchange, "/pret/" + loanId + "?success=echeancier");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.administration(database, database.properties(), database.loans(), session.csrf(), exception.getMessage(), ""));
        }
    }

    private void propertyPage(HttpExchange exchange, Session session, long propertyId) throws IOException {
        Property property = database.property(propertyId);
        if (property == null) { page(exchange, 404, Pages.message("Bien introuvable", "Ce bien n'existe pas.")); return; }
        page(exchange, 200, Pages.propertyDetail(database, property, database.loansForProperty(propertyId), session.csrf()));
    }

    private void propertyAdministrationPage(HttpExchange exchange, Session session, long propertyId) throws IOException {
        Property property = database.property(propertyId);
        if (property == null) { page(exchange, 404, Pages.message("Bien introuvable", "Ce bien n'existe pas.")); return; }
        page(exchange, 200, Pages.propertyAdministration(property, session.csrf()));
    }

    private void financingPage(HttpExchange exchange, Session session, long propertyId) throws IOException {
        Property property = database.property(propertyId);
        if (property == null) { page(exchange, 404, Pages.message("Bien introuvable", "Ce bien n'existe pas.")); return; }
        page(exchange, 200, Pages.financing(property, session.csrf()));
    }

    private void updateProperty(HttpExchange exchange, Session session, long id) throws IOException {
        Map<String, String> form = checkedForm(exchange, session);
        Property current = database.property(id);
        if (current == null) { page(exchange, 404, Pages.message("Bien introuvable", "Ce bien n'existe pas.")); return; }
        try {
            LocalDate purchaseDate = optionalPastDate(form, "purchase_date", "Date d'achat", current.purchaseDate());
            LocalDate estimateDate = optionalPastDate(form, "estimate_date", "Date d'estimation", purchaseDate);
            if (estimateDate.isBefore(purchaseDate)) throw new IllegalArgumentException("L'estimation ne peut pas preceder l'achat.");
            String kind = required(form, "kind", "Type");
            if (!kind.equals("Appartement") && !kind.equals("Maison")) throw new IllegalArgumentException("Type de bien invalide.");
            database.updateProperty(new Property(id, required(form, "name", "Nom"), kind, optionalText(form, "commune", 100, "Commune"), optionalText(form, "address", 200, "Adresse"), "", optionalAmount(form, "area", "Surface"), optionalAmount(form, "purchase", "Cout total d'acquisition"), purchaseDate, optionalAmount(form, "estimate", "Estimation"), estimateDate));
            redirect(exchange, "/administration/bien/" + id);
        } catch (IllegalArgumentException exception) { page(exchange, 400, Pages.propertyAdministration(current, session.csrf())); }
    }

    private void loanPage(HttpExchange exchange, Session session, long loanId) throws IOException {
        Loan loan = database.loan(loanId);
        if (loan == null) {
            page(exchange, 404, Pages.message("Pret introuvable", "Ce pret n'existe pas."));
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String success = query != null && query.contains("success=echeancier") ? "Echeancier importe." : "";
        page(exchange, 200, Pages.loanDetail(loan, database.scheduleLines(loanId), session.csrf(), "", success));
    }

    private void schedulePage(HttpExchange exchange, Session session, long loanId) throws IOException {
        Loan loan = database.loan(loanId);
        if (loan == null) { page(exchange, 404, Pages.message("Pret introuvable", "Ce pret n'existe pas.")); return; }
        page(exchange, 200, Pages.scheduleManagement(loan, session.csrf()));
    }

    private void importSchedule(HttpExchange exchange, Session session, long loanId) throws IOException {
        Loan loan = database.loan(loanId);
        if (loan == null) {
            page(exchange, 404, Pages.message("Pret introuvable", "Ce pret n'existe pas."));
            return;
        }
        try {
            MultipartForm form = multipartForm(exchange);
            if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.UTF_8), form.value("csrf").getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Formulaire expire. Rechargez la page.");
            }
            byte[] csv = form.file("csv");
            if (csv == null || csv.length == 0) throw new IllegalArgumentException("Fichier CSV manquant.");
            database.replaceScheduleLines(loanId, CsvScheduleParser.parse(csv));
            redirect(exchange, "/pret/" + loanId + "/echeancier?success=echeancier");
        } catch (IllegalArgumentException exception) {
            page(exchange, 400, Pages.loanDetail(loan, database.scheduleLines(loanId), session.csrf(), exception.getMessage(), ""));
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

    private static long loanId(String path) { return Long.parseLong(path.substring("/pret/".length())); }
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
            BigDecimal value = new BigDecimal(required(form, key, label).replace(',', '.'));
            if (value.signum() < 0) throw new IllegalArgumentException(label + " ne peut pas etre negatif.");
            if (value.compareTo(new BigDecimal("1000000000")) > 0) throw new IllegalArgumentException(label + " est trop eleve.");
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

    private static BigDecimal optionalAmount(Map<String, String> form, String key, String label) {
        return form.getOrDefault(key, "").isBlank() ? BigDecimal.ZERO : amount(form, key, label);
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

    private record MultipartForm(Map<String, String> values, Map<String, byte[]> files) {
        String value(String name) { return values.getOrDefault(name, ""); }
        byte[] file(String name) { return files.get(name); }
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}
