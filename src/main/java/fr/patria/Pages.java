package fr.patria;

import static fr.patria.Format.area;
import static fr.patria.Format.date;
import static fr.patria.Format.escape;
import static fr.patria.Format.euro;
import static fr.patria.Format.euroWhole;
import static fr.patria.Format.percent;
import static fr.patria.Format.plain;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Pages {
    static final String ASSET_VERSION = assetVersion();

    private Pages() {
    }

    enum Nav { OVERVIEW, ADMIN, NONE }

    record Notice(boolean error, String text) {
        static Notice error(String text) { return text == null || text.isEmpty() ? null : new Notice(true, text); }
        static Notice success(String text) { return text == null || text.isEmpty() ? null : new Notice(false, text); }
    }

    // ---------------------------------------------------------------- Connexion

    static String login(boolean failed, boolean loggedOut, boolean throttled) {
        String notice = throttled ? notice(Notice.error("Trop de tentatives. Réessayez dans quelques minutes."))
                : failed ? notice(Notice.error("Mot de passe incorrect."))
                : loggedOut ? notice(Notice.success("Vous êtes déconnecté.")) : "";
        String content = """
                <main class="login-card">%s<h1>Connexion</h1><p class="muted">Vos données restent sur votre serveur.</p>%s
                <form method="post" action="/connexion" class="stack"><label>Mot de passe<input name="password" type="password" autocomplete="current-password" required autofocus></label>
                <button type="submit">Ouvrir Patria</button></form></main>""".formatted(brand(), notice);
        return document("Connexion · Patria", "login-page", content);
    }

    // ---------------------------------------------------------------- Vue d'ensemble

    static String dashboard(List<Property> properties, List<Loan> loans, Map<Long, List<ScheduleLine>> schedules,
                            List<Contribution> contributions, String csrf) {
        LocalDate today = LocalDate.now();
        if (properties.isEmpty()) {
            String content = heading("Votre patrimoine immobilier", "Vue d'ensemble", "", "")
                    + "<section class=\"card empty-state\"><h2>Aucun bien pour l'instant</h2><p class=\"muted\">Ajoutez votre premier bien pour suivre sa valeur, ses prêts et vos apports.</p><a class=\"button\" href=\"/administration/bien/nouveau\">+ Ajouter un bien</a></section>";
            return layout("Vue d'ensemble · Patria", Nav.OVERVIEW, shell(content), csrf);
        }
        Map<Long, BigDecimal> debtByProperty = new LinkedHashMap<>();
        for (Loan loan : loans) {
            BigDecimal remaining = LoanMath.status(loan, lines(schedules, loan), today).remaining();
            debtByProperty.merge(loan.propertyId(), remaining, BigDecimal::add);
        }
        BigDecimal debt = debtByProperty.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal purchase = properties.stream().map(Property::purchase).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal contributed = contributions.stream().map(Contribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LoanMath.Month next = LoanMath.nextMonth(loans, schedules, today);

        // Le patrimoine global additionne la valeur retenue sur chaque bien : pas de curseur a ce niveau.
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        StringBuilder rows = new StringBuilder();
        for (Property property : properties) {
            BigDecimal retained = Valuation.retained(property);
            BigDecimal propertyDebt = debtByProperty.getOrDefault(property.id(), BigDecimal.ZERO);
            value = value.add(retained);
            net = net.add(Valuation.net(retained, Valuation.saleFeesRate(property), propertyDebt));
            rows.append("<tr><td><a href=\"/bien/").append(property.id()).append("\">").append(escape(property.name())).append("</a><small>").append(escape(property.kind())).append("</small></td>")
                    .append("<td>").append(property.commune().isBlank() ? "–" : escape(property.commune())).append("</td>")
                    .append(numCell(property.area(), area(property.area())))
                    .append(numCell(retained, euroWhole(retained) + "<small>" + escape(Valuation.scenarioName(property)) + "</small>"))
                    .append(numCell(propertyDebt, euroWhole(propertyDebt))).append("</tr>");
        }
        String table = "<div class=\"table-wrap\"><table class=\"data sortable\"><thead><tr><th>Bien</th><th>Commune</th><th class=\"num\">Surface</th><th class=\"num\">Valeur retenue</th><th class=\"num\">Capital restant dû</th></tr></thead><tbody>" + rows + "</tbody></table></div>";

        String content = heading("Votre patrimoine immobilier", "Vue d'ensemble", "", "")
                + "<section class=\"metrics cols-4\">"
                + tile("Valeur estimée", "Somme des valeurs retenues sur chaque bien. Ajustez-les depuis la fiche de chaque bien.", euroWhole(value),
                        properties.size() + (properties.size() > 1 ? " biens" : " bien"))
                + derivedTiles(net, value.subtract(purchase), "Somme, bien par bien, de la valeur retenue − frais de vente et fiscalité − capital restant dû.", "")
                + tile("Capital restant dû", "Capital restant dû à aujourd'hui sur l'ensemble des prêts, d'après les échéanciers.", euroWhole(debt), loans.size() + (loans.size() > 1 ? " prêts" : " prêt"))
                + "</section><section class=\"metrics cols-3\">"
                + nextMonthTile(next, null)
                + tile("Coût d'acquisition", "Somme des coûts d'acquisition (prix, frais et travaux).", euroWhole(purchase), "")
                + tile("Apports", "Somme des apports personnels enregistrés.", euroWhole(contributed), "")
                + "</section>"
                + card("Biens", properties.size(), "<a class=\"button\" href=\"/administration/bien/nouveau\">+ Ajouter un bien</a>", table);
        return layout("Vue d'ensemble · Patria", Nav.OVERVIEW, shell(content), csrf);
    }

    // ---------------------------------------------------------------- Bien

    static String propertyDetail(Property property, List<Loan> loans, Map<Long, List<ScheduleLine>> schedules,
                                 List<Contribution> contributions, Notice notice, String csrf) {
        LocalDate today = LocalDate.now();
        BigDecimal borrowed = BigDecimal.ZERO;
        BigDecimal repaid = BigDecimal.ZERO;
        BigDecimal debt = BigDecimal.ZERO;
        StringBuilder rows = new StringBuilder();
        for (Loan loan : loans) {
            LoanMath.Status status = LoanMath.status(loan, lines(schedules, loan), today);
            borrowed = borrowed.add(status.borrowed());
            repaid = repaid.add(status.repaid());
            debt = debt.add(status.remaining());
            rows.append("<tr><td><a href=\"/pret/").append(loan.id()).append("\">").append(escape(loan.name())).append("</a></td>")
                    .append(numCell(status.borrowed(), euro(status.borrowed())))
                    .append(numCell(status.currentRate(), percent(status.currentRate())))
                    .append(numCell(status.remaining(), euro(status.remaining())))
                    .append("<td data-sort=\"").append(status.progressPercent()).append("\">").append(progress(status.progressPercent())).append("</td>")
                    .append("<td data-sort=\"").append(status.remainingMonths()).append("\">").append(Format.duration(status.remainingMonths())).append("</td></tr>");
        }
        BigDecimal contributed = contributions.stream().map(Contribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Valuation.Scenarios scenarios = Valuation.of(property);
        BigDecimal retained = Valuation.retained(property);
        BigDecimal feesRate = Valuation.saleFeesRate(property);
        LoanMath.Month next = LoanMath.nextMonth(loans, schedules, today);

        String subtitle = escape(property.kind()) + (property.commune().isBlank() ? "" : " · " + escape(property.commune()))
                + (property.area().signum() > 0 ? " · " + area(property.area()) : "") + " · acquis le " + date(property.purchaseDate());
        String actions = "<a class=\"button secondary\" href=\"/administration/bien/" + property.id() + "\">Modifier</a><a class=\"button\" href=\"/administration/bien/" + property.id() + "/financements\">Prêts et apports</a>";
        String loansTable = loans.isEmpty() ? "<p class=\"empty\">Aucun prêt pour ce bien.</p>"
                : "<div class=\"table-wrap\"><table class=\"data sortable\"><thead><tr><th>Prêt</th><th class=\"num\">Emprunté</th><th class=\"num\">Taux actuel</th><th class=\"num\">Restant dû</th><th>Progression</th><th>Durée restante</th></tr></thead><tbody>" + rows + "</tbody></table></div>";
        String contributionRows = contributions.stream().map(item -> "<tr><td><a href=\"/apport/" + item.id() + "/gerer\">" + escape(item.name()) + "</a></td><td data-sort=\"" + item.date() + "\">" + date(item.date()) + "</td>" + numCell(item.amount(), euro(item.amount())) + "</tr>").reduce("", String::concat);
        String contributionsTable = contributions.isEmpty() ? "<p class=\"empty\">Aucun apport enregistré.</p>"
                : "<div class=\"table-wrap\"><table class=\"data sortable\"><thead><tr><th>Apport</th><th>Date</th><th class=\"num\">Montant</th></tr></thead><tbody>" + contributionRows + "</tbody><tfoot><tr><td colspan=\"2\">Total</td><td class=\"num\">" + euro(contributed) + "</td></tr></tfoot></table></div>";
        String recurring = property.recurringFeesAnnual().signum() > 0 ? "Frais récurrents : " + euroWhole(property.recurringFeesAnnual()) + "/an, non déduits" : "";

        String hero = scenarios.isEmpty()
                ? "<section class=\"card empty-state\"><h2>Aucune valeur renseignée</h2><p class=\"muted\">Indiquez le coût d'acquisition ou une estimation pour suivre la valeur de ce bien.</p><a class=\"button\" href=\"/administration/bien/" + property.id() + "\">Renseigner</a></section>"
                : estimateHero("/bien/" + property.id() + "/valeur", scenarios, retained, Valuation.scenarioName(property), feesRate, debt, property.purchase(),
                        property.estimate().signum() == 0 ? "Aucune estimation : la valeur retenue est le coût d'acquisition." : "Estimation du " + date(property.estimateDate()) + ".");
        String content = "<p><a class=\"back-link\" href=\"/\">← Vue d'ensemble</a></p>"
                + heading("Bien", property.name(), subtitle, actions) + notice(notice) + hero
                + "<section class=\"metrics cols-4\">"
                + derivedTiles(Valuation.net(retained, feesRate, debt), retained.subtract(property.purchase()),
                        "Valeur retenue − frais de vente et fiscalité (" + Format.ratioPercent(feesRate) + ") − capital restant dû.", recurring)
                + tile("Capital restant dû", "Capital restant dû à aujourd'hui, d'après les échéanciers.", euroWhole(debt), "")
                + nextMonthTile(next, "/bien/" + property.id() + "/mensualites")
                + "</section><section class=\"metrics cols-4\">"
                + tile("Coût d'acquisition", "Montant total engagé pour acheter le bien : prix, frais et travaux.", euroWhole(property.purchase()), date(property.purchaseDate()))
                + tile("Capital emprunté", "Total des déblocages des prêts.", euroWhole(borrowed), "")
                + tile("Capital remboursé", "Part de capital des échéances déjà passées.", euroWhole(repaid), "")
                + tile("Apports", "Somme des apports personnels enregistrés pour ce bien.", euroWhole(contributed), "")
                + "</section>"
                + card("Prêts", loans.size(), "<a class=\"button secondary\" href=\"/bien/" + property.id() + "/mensualites\">Mensualités</a><a class=\"button\" href=\"/administration/bien/" + property.id() + "/financements\">+ Ajouter</a>", loansTable)
                + card("Apports", contributions.size(), "<a class=\"button\" href=\"/administration/bien/" + property.id() + "/financements#apports\">+ Ajouter</a>", contributionsTable);
        return layout(property.name() + " · Patria", Nav.OVERVIEW, shell(content), csrf);
    }

    static Map<String, String> propertyValues(Property property) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", property.name());
        values.put("kind", property.kind());
        values.put("commune", property.commune());
        values.put("address", property.address());
        values.put("area", plain(property.area()));
        values.put("purchase", plain(property.purchase()));
        values.put("purchase_date", property.purchaseDate().toString());
        values.put("estimate", plain(property.estimate()));
        values.put("estimate_high", plain(property.estimateHigh()));
        values.put("estimate_date", property.estimateDate().toString());
        values.put("sale_fees_pct", plain(Valuation.saleFeesRate(property).multiply(BigDecimal.valueOf(100))));
        values.put("recurring_fees_annual", plain(property.recurringFeesAnnual()));
        return values;
    }

    /** Formulaire de creation (property == null) ou de modification d'un bien. */
    static String propertyForm(Property property, Map<String, String> values, Notice notice, String csrf) {
        boolean creating = property == null;
        String kind = values.getOrDefault("kind", "Appartement");
        String action = creating ? "/bien" : "/administration/bien/" + property.id() + "/proprietes";
        String form = "<form method=\"post\" action=\"" + action + "\" class=\"stack\">" + csrfField(csrf)
                + "<section class=\"card section\"><h2>Informations</h2><div class=\"form-grid\">"
                + input("Nom du bien", "name", values, "required maxlength=\"100\"", true)
                + "<label>Type<select name=\"kind\">" + option("Appartement", kind) + option("Maison", kind) + "</select></label>"
                + input("Commune", "commune", values, "maxlength=\"100\"", false)
                + input("Surface (m²)", "area", values, "inputmode=\"decimal\"", false)
                + "</div>" + input("Adresse", "address", values, "maxlength=\"200\"", false) + "</section>"
                + "<section class=\"card section\"><h2>Acquisition</h2><div class=\"form-grid\">"
                + input("Coût d'acquisition (€)", "purchase", values, "data-money", false, "Prix, frais de notaire et travaux inclus.")
                + input("Date d'acquisition", "purchase_date", values, "type=\"date\"", false)
                + "</div></section>"
                + "<section class=\"card section\"><h2>Estimation</h2><div class=\"form-grid\">"
                + input("Estimation prudente (€)", "estimate", values, "data-money", false, "Vide : le coût d'acquisition est utilisé.")
                + input("Estimation optimiste (€)", "estimate_high", values, "data-money", false, "Vide : identique à l'estimation prudente.")
                + input("Date d'estimation", "estimate_date", values, "type=\"date\"", false)
                + "</div></section>"
                + "<section class=\"card section\"><h2>Valeur nette</h2><div class=\"form-grid\">"
                + input("Frais de vente et fiscalité (%)", "sale_fees_pct", values, "inputmode=\"decimal\" placeholder=\"8\"", false, "Pourcentage retiré de la valeur estimée. 8 % par défaut.")
                + input("Frais récurrents annuels (€)", "recurring_fees_annual", values, "data-money", false, "Taxe foncière, charges… Affichés à titre indicatif.")
                + "</div><p class=\"muted\">Valeur nette indicative = valeur estimée − frais de vente et fiscalité − capital restant dû.</p></section>"
                + "<div class=\"form-actions\"><button type=\"submit\">" + (creating ? "Créer le bien" : "Enregistrer") + "</button></div></form>";
        if (creating) {
            String content = "<p><a class=\"back-link\" href=\"/administration\">← Administration</a></p>" + heading("Administration", "Nouveau bien", "", "") + notice(notice) + form;
            return layout("Nouveau bien · Patria", Nav.ADMIN, shell(content), csrf);
        }
        String danger = "<section class=\"card section danger-zone\"><h2>Supprimer ce bien</h2><p class=\"muted\">Supprime définitivement le bien, ses prêts, leurs échéanciers et ses apports.</p>"
                + "<form method=\"post\" action=\"/administration/bien/" + property.id() + "/supprimer\" data-confirm=\"Supprimer définitivement « " + escape(property.name()) + " » et toutes ses données ?\">" + csrfField(csrf)
                + "<button class=\"danger\" type=\"submit\">Supprimer le bien</button></form></section>";
        String content = adminShell(property, "proprietes", heading("Bien", "Modifier", escape(property.name()), "") + notice(notice) + form + danger);
        return layout("Modifier · " + property.name() + " · Patria", Nav.ADMIN, content, csrf);
    }

    static String financing(Property property, List<Loan> loans, Map<Long, List<ScheduleLine>> schedules, List<Contribution> contributions,
                            Notice notice, Map<String, String> values, String csrf) {
        LocalDate today = LocalDate.now();
        StringBuilder loanRows = new StringBuilder();
        for (Loan loan : loans) {
            LoanMath.Status status = LoanMath.status(loan, lines(schedules, loan), today);
            loanRows.append("<tr><td><a href=\"/pret/").append(loan.id()).append("\">").append(escape(loan.name())).append("</a></td>")
                    .append(numCell(status.borrowed(), euro(status.borrowed()))).append(numCell(status.remaining(), euro(status.remaining())))
                    .append("<td class=\"actions\"><a href=\"/pret/").append(loan.id()).append("/echeancier\">Gérer</a></td></tr>");
        }
        String loansTable = loans.isEmpty() ? "<p class=\"empty\">Aucun prêt.</p>"
                : "<div class=\"table-wrap\"><table class=\"data\"><thead><tr><th>Prêt</th><th class=\"num\">Emprunté</th><th class=\"num\">Restant dû</th><th></th></tr></thead><tbody>" + loanRows + "</tbody></table></div>";
        String contributionRows = contributions.stream().map(item -> "<tr><td>" + escape(item.name()) + "</td><td>" + date(item.date()) + "</td>" + numCell(item.amount(), euro(item.amount()))
                + "<td class=\"actions\"><a href=\"/apport/" + item.id() + "/gerer\">Modifier</a></td></tr>").reduce("", String::concat);
        String contributionsTable = contributions.isEmpty() ? "<p class=\"empty\">Aucun apport.</p>"
                : "<div class=\"table-wrap\"><table class=\"data\"><thead><tr><th>Apport</th><th>Date</th><th class=\"num\">Montant</th><th></th></tr></thead><tbody>" + contributionRows + "</tbody></table></div>";
        Map<String, String> contributionValues = new LinkedHashMap<>(values);
        contributionValues.putIfAbsent("date", today.toString());
        String loanForm = "<form method=\"post\" action=\"/pret\" enctype=\"multipart/form-data\" class=\"stack inline-form\">" + csrfField(csrf)
                + "<input type=\"hidden\" name=\"property_id\" value=\"" + property.id() + "\"><h3>Ajouter un prêt</h3><div class=\"form-grid\">"
                + input("Nom du prêt", "loan_name", values, "required maxlength=\"100\" placeholder=\"Prêt principal\"", true)
                + "<label>Échéancier CSV <span class=\"required-mark\">*</span><input name=\"csv\" type=\"file\" accept=\".csv,text/csv\" required></label></div>"
                + "<p class=\"muted\"><a href=\"/administration#format-csv\">Format du fichier CSV</a></p><div class=\"form-actions\"><button type=\"submit\">Importer le prêt</button></div></form>";
        String contributionForm = "<form method=\"post\" action=\"/administration/bien/" + property.id() + "/apports\" class=\"stack inline-form\">" + csrfField(csrf)
                + "<h3>Ajouter un apport</h3><div class=\"form-grid cols-3\">"
                + input("Libellé", "name", contributionValues, "required maxlength=\"100\" placeholder=\"Épargne personnelle\"", true)
                + input("Date", "date", contributionValues, "type=\"date\" required", true)
                + input("Montant (€)", "amount", contributionValues, "data-money required", true)
                + "</div><div class=\"form-actions\"><button type=\"submit\">Ajouter l'apport</button></div></form>";
        String body = heading("Bien", "Prêts et apports", escape(property.name()), "") + notice(notice)
                + card("Prêts", loans.size(), "", loansTable + loanForm)
                + "<div id=\"apports\">" + card("Apports", contributions.size(), "", contributionsTable + contributionForm) + "</div>";
        return layout("Prêts et apports · " + property.name() + " · Patria", Nav.ADMIN, adminShell(property, "financements", body), csrf);
    }

    static String monthlyPayments(Property property, List<Loan> loans, Map<Long, List<ScheduleLine>> schedules, String csrf) {
        LocalDate today = LocalDate.now();
        List<LoanMath.Month> months = LoanMath.months(loans, schedules, today);
        LoanMath.Month next = months.stream().filter(month -> !month.paid()).findFirst().orElse(null);
        StringBuilder rows = new StringBuilder();
        for (LoanMath.Month month : months) {
            StringBuilder details = new StringBuilder();
            month.byLoan().forEach((name, amount) -> details.append("<span class=\"detail\">").append(escape(name)).append(" · ").append(euro(amount)).append("</span>"));
            rows.append("<tr").append(month == next ? " class=\"highlight\"" : "").append("><td data-sort=\"").append(month.month()).append("\">").append(Format.month(month.month())).append("</td><td>")
                    .append(month.paid() ? status(true) : status(false)).append("</td>").append(numCell(month.total(), euro(month.total()))).append("<td>").append(details).append("</td></tr>");
        }
        String table = months.isEmpty() ? "<p class=\"empty\">Aucune échéance importée.</p>"
                : "<div class=\"table-wrap\"><table class=\"data sortable\" data-filter><thead><tr><th>Mois</th><th>Statut</th><th class=\"num\">Total</th><th>Par prêt</th></tr></thead><tbody>" + rows + "</tbody></table></div>";
        String content = "<p><a class=\"back-link\" href=\"/bien/" + property.id() + "\">← " + escape(property.name()) + "</a></p>"
                + heading("Mensualités", property.name(), "Total des échéances par mois, tous prêts confondus.", "")
                + card("Échéances", months.size(), "", table);
        return layout("Mensualités · " + property.name() + " · Patria", Nav.OVERVIEW, shell(content), csrf);
    }

    // ---------------------------------------------------------------- Apports

    static Map<String, String> contributionValues(Contribution contribution) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", contribution.name());
        values.put("date", contribution.date().toString());
        values.put("amount", plain(contribution.amount()));
        return values;
    }

    static String contributionManagement(Contribution contribution, Property property, Map<String, String> values, Notice notice, String csrf) {
        String form = "<section class=\"card section\"><form method=\"post\" action=\"/apport/" + contribution.id() + "/gerer\" class=\"stack\">" + csrfField(csrf)
                + "<div class=\"form-grid cols-3\">" + input("Libellé", "name", values, "required maxlength=\"100\"", true)
                + input("Date", "date", values, "type=\"date\" required", true) + input("Montant (€)", "amount", values, "data-money required", true)
                + "</div><div class=\"form-actions\"><button type=\"submit\">Enregistrer</button></div></form></section>";
        String danger = "<section class=\"card section danger-zone\"><h2>Supprimer cet apport</h2><form method=\"post\" action=\"/apport/" + contribution.id() + "/supprimer\" data-confirm=\"Supprimer l'apport « " + escape(contribution.name()) + " » ?\">"
                + csrfField(csrf) + "<button class=\"danger\" type=\"submit\">Supprimer l'apport</button></form></section>";
        String content = "<p><a class=\"back-link\" href=\"/bien/" + property.id() + "\">← " + escape(property.name()) + "</a></p>"
                + heading("Apport", contribution.name(), escape(property.name()), "") + notice(notice) + form + danger;
        return layout("Apport · " + contribution.name() + " · Patria", Nav.OVERVIEW, shell(content), csrf);
    }

    // ---------------------------------------------------------------- Administration

    static String administration(List<Property> properties, List<Loan> loans, Map<Long, List<ScheduleLine>> schedules, Notice notice, String csrf) {
        LocalDate today = LocalDate.now();
        Map<Long, Integer> loanCounts = new LinkedHashMap<>();
        StringBuilder loanRows = new StringBuilder();
        for (Loan loan : loans) {
            loanCounts.merge(loan.propertyId(), 1, Integer::sum);
            LoanMath.Status status = LoanMath.status(loan, lines(schedules, loan), today);
            loanRows.append("<tr><td><a href=\"/pret/").append(loan.id()).append("\">").append(escape(loan.name())).append("</a></td><td>").append(escape(loan.propertyName())).append("</td>")
                    .append(numCell(status.borrowed(), euro(status.borrowed()))).append(numCell(status.remaining(), euro(status.remaining())))
                    .append(numCell(status.currentRate(), percent(status.currentRate())))
                    .append("<td class=\"actions\"><a href=\"/pret/").append(loan.id()).append("/echeancier\">Gérer</a></td></tr>");
        }
        StringBuilder propertyRows = new StringBuilder();
        for (Property property : properties) {
            propertyRows.append("<tr><td><a href=\"/bien/").append(property.id()).append("\">").append(escape(property.name())).append("</a></td><td>").append(escape(property.kind())).append("</td><td>")
                    .append(property.commune().isBlank() ? "–" : escape(property.commune())).append("</td><td class=\"num\">").append(loanCounts.getOrDefault(property.id(), 0)).append("</td>")
                    .append("<td class=\"actions\"><a href=\"/administration/bien/").append(property.id()).append("\">Modifier</a><a href=\"/administration/bien/").append(property.id()).append("/financements\">Prêts et apports</a></td></tr>");
        }
        String propertiesTable = properties.isEmpty() ? "<p class=\"empty\">Aucun bien enregistré.</p>"
                : "<div class=\"table-wrap\"><table class=\"data sortable\"><thead><tr><th>Bien</th><th>Type</th><th>Commune</th><th class=\"num\">Prêts</th><th></th></tr></thead><tbody>" + propertyRows + "</tbody></table></div>";
        String loansTable = loans.isEmpty() ? "<p class=\"empty\">Aucun prêt enregistré. Ajoutez-en un depuis la page « Prêts et apports » d'un bien.</p>"
                : "<div class=\"table-wrap\"><table class=\"data sortable\"><thead><tr><th>Prêt</th><th>Bien</th><th class=\"num\">Emprunté</th><th class=\"num\">Restant dû</th><th class=\"num\">Taux actuel</th><th></th></tr></thead><tbody>" + loanRows + "</tbody></table></div>";
        String csvHelp = """
                <section class="card section" id="format-csv"><h2>Format des échéanciers CSV</h2>
                <p class="muted">Fichier UTF-8 de 1 Mio maximum, séparateur virgule ou point-virgule, sans guillemets. Les nombres acceptent la virgule ou le point décimal. Un nouvel import remplace l'échéancier existant.</p>
                <pre><code>date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct
                2026-01-05,ECHEANCE,850.00,500.00,330.00,20.00,14500.00,3.20</code></pre>
                <p class="muted"><code>date</code> (AAAA-MM-JJ) et <code>type</code> sont obligatoires. Types : <code>DEBLOCAGE</code>, <code>ECHEANCE</code>, <code>REMBOURSEMENT_ANTICIPE</code>, <code>CONSOLIDATION</code>, <code>AJUSTEMENT</code>. Chaque ligne doit contenir au moins un montant.</p></section>""";
        String content = heading("Administration", "Biens et prêts", "", "<a class=\"button\" href=\"/administration/bien/nouveau\">+ Nouveau bien</a>") + notice(notice)
                + card("Biens", properties.size(), "", propertiesTable) + card("Prêts", loans.size(), "", loansTable) + csvHelp;
        return layout("Administration · Patria", Nav.ADMIN, shell(content), csrf);
    }

    // ---------------------------------------------------------------- Pret

    static String loanDetail(Loan loan, List<ScheduleLine> lines, Notice notice, String csrf) {
        LocalDate today = LocalDate.now();
        LoanMath.Status status = LoanMath.status(loan, lines, today);
        StringBuilder rows = new StringBuilder();
        for (ScheduleLine line : lines) {
            boolean past = LoanMath.isPast(line, today);
            rows.append("<tr").append(line == status.nextInstallment() ? " class=\"highlight\"" : "").append("><td data-sort=\"").append(line.date()).append("\">").append(date(line.date())).append("</td><td>").append(typeLabel(line.type())).append("</td><td>")
                    .append(status(past)).append("</td>")
                    .append(numCell(line.amount(), euro(line.amount()))).append(numCell(line.principalRepaid(), euro(line.principalRepaid())))
                    .append(numCell(line.interest(), euro(line.interest()))).append(numCell(line.fees(), euro(line.fees())))
                    .append(numCell(line.remainingPrincipal(), euro(line.remainingPrincipal()))).append(numCell(line.annualRate(), percent(line.annualRate()))).append("</tr>");
        }
        String table = lines.isEmpty() ? "<p class=\"empty\">Aucune échéance importée.</p>"
                : "<div class=\"table-wrap\"><table class=\"data sortable\" data-filter><thead><tr><th>Date</th><th>Type</th><th>Statut</th><th class=\"num\">Montant</th><th class=\"num\">Capital</th><th class=\"num\">Intérêts</th><th class=\"num\">Frais</th><th class=\"num\">Restant dû</th><th class=\"num\">Taux</th></tr></thead><tbody>" + rows + "</tbody></table></div>";
        ScheduleLine next = status.nextInstallment();
        String content = "<p><a class=\"back-link\" href=\"/bien/" + loan.propertyId() + "\">← " + escape(loan.propertyName()) + "</a></p>"
                + heading("Prêt", loan.name(), "Finance « " + escape(loan.propertyName()) + " »", "<a class=\"button secondary\" href=\"/pret/" + loan.id() + "/echeancier\">Gérer le prêt</a>")
                + notice(notice)
                + "<section class=\"metrics cols-3\">"
                + tile("Capital emprunté", "Total des déblocages enregistrés dans l'échéancier.", euro(status.borrowed()), "")
                + tile("Capital restant dû", "Capital restant dû à aujourd'hui. Avant la première échéance, c'est le capital emprunté.", euro(status.remaining()), status.progressPercent() + " % remboursé")
                + tile("Capital remboursé", "Part de capital des échéances déjà passées.", euro(status.repaid()), "")
                + tile("Taux actuel", "Taux annuel de la dernière échéance passée.", percent(status.currentRate()), "")
                + tile("Prochaine échéance", null, next == null ? "–" : euro(next.amount()), next == null ? "Aucune échéance à venir" : date(next.date()))
                + tile("Durée restante", "Durée jusqu'à la dernière échéance connue.", Format.duration(status.remainingMonths()), status.lastDate() == null ? "" : "Fin le " + date(status.lastDate()))
                + "</section>"
                + "<section class=\"card section\"><div class=\"section-title\"><h2>Progression</h2><span class=\"muted\">" + status.progressPercent() + " % du capital emprunté" + "</span></div>" + progress(status.progressPercent()) + "</section>"
                + card("Échéancier", lines.size(), "", table);
        return layout("Prêt · " + loan.name() + " · Patria", Nav.OVERVIEW, shell(content), csrf);
    }

    static String loanManagement(Loan loan, Notice notice, String csrf) {
        String rename = "<section class=\"card section\"><h2>Nom du prêt</h2><form method=\"post\" action=\"/pret/" + loan.id() + "/renommer\" class=\"stack\">" + csrfField(csrf)
                + input("Nom", "name", Map.of("name", loan.name()), "required maxlength=\"100\"", true) + "<div class=\"form-actions\"><button type=\"submit\">Renommer</button></div></form></section>";
        String schedule = "<section class=\"card section\"><h2>Remplacer l'échéancier</h2><p class=\"muted\">Le nouvel import remplace toutes les lignes existantes et recalcule le prêt. <a href=\"/administration#format-csv\">Format du fichier</a></p>"
                + "<form method=\"post\" action=\"/pret/" + loan.id() + "/echeancier\" enctype=\"multipart/form-data\" class=\"stack\">" + csrfField(csrf)
                + "<label>Fichier CSV <span class=\"required-mark\">*</span><input name=\"csv\" type=\"file\" accept=\".csv,text/csv\" required></label><div class=\"form-actions\"><button type=\"submit\">Importer</button></div></form></section>";
        String danger = "<section class=\"card section danger-zone\"><h2>Supprimer ce prêt</h2><p class=\"muted\">Supprime le prêt et son échéancier.</p><form method=\"post\" action=\"/pret/" + loan.id() + "/supprimer\" data-confirm=\"Supprimer le prêt « " + escape(loan.name()) + " » ?\">"
                + csrfField(csrf) + "<button class=\"danger\" type=\"submit\">Supprimer le prêt</button></form></section>";
        String content = "<p><a class=\"back-link\" href=\"/pret/" + loan.id() + "\">← " + escape(loan.name()) + "</a></p>"
                + heading("Prêt", "Gérer le prêt", escape(loan.name()) + " · " + escape(loan.propertyName()), "") + notice(notice) + rename + schedule + danger;
        return layout("Gérer · " + loan.name() + " · Patria", Nav.OVERVIEW, shell(content), csrf);
    }

    // ---------------------------------------------------------------- Messages

    static String message(String title, String text, String csrf) {
        String content = "<section class=\"card empty-state\"><h1>" + escape(title) + "</h1><p class=\"muted\">" + escape(text) + "</p><a class=\"button secondary\" href=\"/\">Retour à la vue d'ensemble</a></section>";
        return layout(title + " · Patria", Nav.NONE, shell(content), csrf);
    }

    // ---------------------------------------------------------------- Composants

    /** Curseur d'estimation d'un bien. Chaque deplacement est enregistre sur le bien (saveUrl). */
    private static String estimateHero(String saveUrl, Valuation.Scenarios s, BigDecimal value, String scenario, BigDecimal feesRate, BigDecimal debt, BigDecimal purchase, String caption) {
        BigDecimal min = Valuation.sliderMin(s);
        BigDecimal max = Valuation.sliderMax(s);
        String chips = chip("pessimistic", "Pessimiste", s.pessimistic(), value, "Estimation prudente − 5 %")
                + chip("prudent", "Prudent", s.prudent(), value, "Estimation prudente")
                + chip("middle", "Fourchette", s.middle(), value, "Milieu entre estimations prudente et optimiste")
                + chip("optimistic", "Optimiste", s.optimistic(), value, "Estimation optimiste");
        return "<section class=\"card hero\" data-estimate data-save=\"" + escape(saveUrl) + "\" data-fees=\"" + plain(feesRate) + "\" data-debt=\"" + plain(debt) + "\" data-purchase=\"" + plain(purchase) + "\""
                + " data-pessimistic=\"" + plain(s.pessimistic()) + "\" data-prudent=\"" + plain(s.prudent()) + "\" data-middle=\"" + plain(s.middle()) + "\" data-optimistic=\"" + plain(s.optimistic()) + "\">"
                + "<div class=\"hero-head\"><div><span class=\"metric-label\">Valeur retenue</span><strong class=\"hero-value\" data-estimate-value>" + euroWhole(value) + "</strong>"
                + "<span class=\"muted\">" + escape(caption) + " <span class=\"save-state\" data-save-state aria-live=\"polite\"></span></span></div><span class=\"scenario-badge\" data-estimate-label>" + escape(scenario) + "</span></div>"
                + "<input class=\"slider\" type=\"range\" min=\"" + plain(min) + "\" max=\"" + plain(max) + "\" step=\"10000\" value=\"" + plain(value) + "\" aria-label=\"Valeur retenue\">"
                + "<div class=\"chips\">" + chips + "</div></section>";
    }

    private static String chip(String name, String label, BigDecimal value, BigDecimal current, String title) {
        return "<button type=\"button\" class=\"chip" + (value.compareTo(current) == 0 ? " active" : "") + "\" data-scenario=\"" + name + "\" data-value=\"" + plain(value) + "\" title=\"" + escape(title) + "\"><span>" + label + "</span><b>" + euroWhole(value) + "</b></button>";
    }

    /** Tuiles « Valeur nette » et « Plus-value ». Sur la fiche d'un bien, le curseur les recalcule en direct. */
    private static String derivedTiles(BigDecimal net, BigDecimal gain, String netHint, String netNote) {
        return "<article class=\"metric\"><span class=\"metric-label\" title=\"" + escape(netHint) + "\">Valeur nette indicative</span><strong data-derived=\"net\">" + euroWhole(net) + "</strong>" + (netNote.isEmpty() ? "" : "<small>" + netNote + "</small>") + "</article>"
                + "<article class=\"metric\"><span class=\"metric-label\" title=\"Valeur retenue − coût d'acquisition. Hors frais de vente et fiscalité.\">Plus-value latente</span><strong data-derived=\"gain\" class=\"" + (gain.signum() < 0 ? "negative" : gain.signum() > 0 ? "positive" : "") + "\">" + Format.signedEuroWhole(gain) + "</strong></article>";
    }

    private static String nextMonthTile(LoanMath.Month next, String link) {
        String sub = next == null ? "Aucune échéance à venir" : Format.month(next.month()) + (next.byLoan().size() > 1 ? " · " + next.byLoan().size() + " prêts" : "");
        if (link != null) sub += " · <a href=\"" + link + "\">Détail</a>";
        return tile("Prochaine mensualité", "Total des échéances du prochain mois, tous prêts confondus.", next == null ? "–" : euro(next.total()), sub);
    }

    private static String tile(String label, String hint, String value, String sub) {
        return "<article class=\"metric\"><span class=\"metric-label\"" + (hint == null ? "" : " title=\"" + escape(hint) + "\"") + ">" + escape(label) + "</span><strong>" + value + "</strong>"
                + (sub == null || sub.isEmpty() ? "" : "<small>" + sub + "</small>") + "</article>";
    }

    private static String heading(String eyebrow, String title, String subtitleHtml, String actionsHtml) {
        return "<section class=\"page-heading\"><div><p class=\"eyebrow\">" + escape(eyebrow) + "</p><h1>" + escape(title) + "</h1>"
                + (subtitleHtml.isEmpty() ? "" : "<p class=\"muted\">" + subtitleHtml + "</p>") + "</div>"
                + (actionsHtml.isEmpty() ? "" : "<div class=\"heading-actions\">" + actionsHtml + "</div>") + "</section>";
    }

    private static String card(String title, int count, String actionsHtml, String body) {
        return "<section class=\"card section\"><div class=\"section-title\"><h2>" + escape(title) + " <span class=\"count\">" + count + "</span></h2>"
                + (actionsHtml.isEmpty() ? "" : "<div class=\"heading-actions\">" + actionsHtml + "</div>") + "</div>" + body + "</section>";
    }

    private static String adminShell(Property property, String active, String body) {
        String nav = "<aside class=\"admin-nav\"><p class=\"eyebrow\">" + escape(property.name()) + "</p>"
                + navLink("/administration/bien/" + property.id(), "Informations", active.equals("proprietes"))
                + navLink("/administration/bien/" + property.id() + "/financements", "Prêts et apports", active.equals("financements"))
                + navLink("/bien/" + property.id(), "Consulter le bien →", false) + "</aside>";
        return "<main class=\"shell admin-layout\">" + nav + "<div>" + body + "</div></main>";
    }

    private static String navLink(String href, String label, boolean active) {
        return "<a href=\"" + href + "\"" + (active ? " class=\"active\" aria-current=\"page\"" : "") + ">" + escape(label) + "</a>";
    }

    private static String input(String label, String name, Map<String, String> values, String attributes, boolean required) {
        return input(label, name, values, attributes, required, null);
    }

    private static String input(String label, String name, Map<String, String> values, String attributes, boolean required, String help) {
        return "<label>" + escape(label) + (required ? " <span class=\"required-mark\">*</span>" : "") + "<input name=\"" + name + "\" value=\"" + escape(values.getOrDefault(name, "")) + "\" " + attributes + ">"
                + (help == null ? "" : "<span class=\"help\">" + escape(help) + "</span>") + "</label>";
    }

    private static String option(String value, String selected) {
        return "<option" + (value.equals(selected) ? " selected" : "") + ">" + escape(value) + "</option>";
    }

    private static String csrfField(String csrf) {
        return "<input type=\"hidden\" name=\"csrf\" value=\"" + escape(csrf) + "\">";
    }

    private static String numCell(BigDecimal sort, String html) {
        return "<td class=\"num\"" + (sort == null ? "" : " data-sort=\"" + plain(sort) + "\"") + ">" + html + "</td>";
    }

    private static String progress(int percent) {
        return "<div class=\"progress-cell\"><div class=\"progress\" role=\"progressbar\" aria-valuenow=\"" + percent + "\" aria-valuemin=\"0\" aria-valuemax=\"100\"><span style=\"width:" + percent + "%\"></span></div><small>" + percent + " %</small></div>";
    }

    private static String status(boolean paid) {
        return paid ? "<span class=\"badge paid\">Payée</span>" : "<span class=\"badge\">À venir</span>";
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "DEBLOCAGE" -> "Déblocage";
            case "ECHEANCE" -> "Échéance";
            case "REMBOURSEMENT_ANTICIPE" -> "Remboursement anticipé";
            case "CONSOLIDATION" -> "Consolidation";
            case "AJUSTEMENT" -> "Ajustement";
            default -> escape(type);
        };
    }

    private static String notice(Notice notice) {
        if (notice == null) return "";
        return "<p class=\"notice " + (notice.error() ? "error" : "success") + "\" role=\"" + (notice.error() ? "alert" : "status") + "\">" + escape(notice.text()) + "</p>";
    }

    private static List<ScheduleLine> lines(Map<Long, List<ScheduleLine>> schedules, Loan loan) {
        return schedules.getOrDefault(loan.id(), List.of());
    }

    private static String shell(String content) {
        return "<main class=\"shell\">" + content + "</main>";
    }

    private static String layout(String title, Nav active, String content, String csrf) {
        String header = csrf == null ? "" : "<header class=\"topbar\"><div class=\"shell navigation\">" + brand() + "<nav>"
                + navLink("/", "Vue d'ensemble", active == Nav.OVERVIEW) + navLink("/administration", "Administration", active == Nav.ADMIN)
                + "</nav><form method=\"post\" action=\"/deconnexion\">" + csrfField(csrf) + "<button class=\"quiet logout\" type=\"submit\" aria-label=\"Déconnexion\" title=\"Déconnexion\"><svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/></svg><span>Déconnexion</span></button></form></div></header>";
        return document(title, "", header + content);
    }

    private static String document(String title, String bodyClass, String body) {
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + escape(title) + "</title>"
                + "<link rel=\"icon\" type=\"image/png\" href=\"/images/patria-logo.png?v=" + ASSET_VERSION + "\"><link rel=\"stylesheet\" href=\"/css/patria.css?v=" + ASSET_VERSION + "\">"
                + "<script defer src=\"/js/app.js?v=" + ASSET_VERSION + "\"></script></head><body" + (bodyClass.isEmpty() ? "" : " class=\"" + bodyClass + "\"") + ">" + body + "</body></html>";
    }

    private static String brand() {
        return "<a class=\"brand\" href=\"/\"><img class=\"brand-mark\" src=\"/images/patria-logo.png?v=" + ASSET_VERSION + "\" width=\"30\" height=\"30\" alt=\"\"><span>Patria</span></a>";
    }

    /** Empreinte des fichiers statiques : change a chaque modification, ce qui invalide le cache navigateur. */
    private static String assetVersion() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : List.of("static/css/patria.css", "static/js/app.js", "static/images/patria-logo.png")) {
                try (InputStream input = Pages.class.getClassLoader().getResourceAsStream(path)) {
                    if (input != null) digest.update(input.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 10);
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "1";
        }
    }
}
