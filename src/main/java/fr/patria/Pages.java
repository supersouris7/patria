package fr.patria;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

final class Pages {
    private static final NumberFormat EURO = NumberFormat.getCurrencyInstance(Locale.FRANCE);

    private Pages() {
    }

    static String login(boolean failed, boolean loggedOut) {
        String notice = failed ? "<p class=\"notice error\">Mot de passe incorrect.</p>" : loggedOut ? "<p class=\"notice success\">Vous etes deconnecte.</p>" : "";
        return """
                <!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Connexion · Patria</title><link rel="icon" type="image/png" href="/images/patria-logo.png"><link rel="stylesheet" href="/css/patria.css?v=3"></head>
                <body class="login-page"><main class="login-card">%s<h1>Connexion</h1>
                <p class="muted">Vos donnees restent sur votre serveur.</p>%s<form method="post" action="/connexion" class="stack">
                <label>Mot de passe <span class="required-mark">*</span><input name="password" type="password" autocomplete="current-password" required autofocus></label><button type="submit">Ouvrir Patria</button>
                </form></main></body></html>
                """.formatted(brand(), notice);
    }

    static String dashboard(Database database, List<Property> properties, List<Loan> loans, String csrf) {
        BigDecimal value = properties.stream().map(Pages::estimate).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debt = loans.stream().map(loan -> remaining(loan, database.scheduleLines(loan.id()))).reduce(BigDecimal.ZERO, BigDecimal::add);
        StringBuilder rows = new StringBuilder();
        for (Property property : properties) {
            rows.append("<tr><td><strong><a href=\"/bien/").append(property.id()).append("\">").append(escape(property.name())).append("</a></strong></td><td>").append(escape(property.commune())).append("</td><td>").append(property.area())
                    .append(" m2</td><td>").append(euro(estimate(property))).append("</td></tr>");
        }
        String content = """
                <main class="shell"><section class="page-heading"><div><p class="eyebrow">VOTRE PATRIMOINE IMMOBILIER</p>
                <h1>Vue d'ensemble<span>.</span></h1><p class="muted">Une synthese simple de vos biens et financements.</p></div>
                <a class="button" href="/administration">Gerer mes biens</a></section>
                <section class="metrics"><article class="metric"><span>Estimation des biens</span><strong>%s</strong></article>
                <article class="metric"><span>Capital restant du</span><strong>%s</strong></article><article class="metric"><span>Valeur nette indicative</span><strong>%s</strong></article></section>
                <section class="card section"><div class="section-title"><div><p class="eyebrow">VOS BIENS</p><h2>Biens enregistres</h2></div><span class="count">%d</span></div>%s</section></main>
                """.formatted(euro(value), euro(debt), euro(value.subtract(debt)), properties.size(), properties.isEmpty()
                        ? "<p class=\"empty\">Ajoutez votre premier bien depuis l'administration.</p>"
                        : "<div class=\"table-wrap\"><table><thead><tr><th>Bien</th><th>Commune</th><th>Surface</th><th>Estimation</th></tr></thead><tbody>" + rows + "</tbody></table></div>");
        return layout("Vue d'ensemble · Patria", content, csrf);
    }

    static String propertyDetail(Database database, Property property, List<Loan> loans, String csrf) {
        StringBuilder rows = new StringBuilder();
        BigDecimal borrowed = BigDecimal.ZERO;
        BigDecimal repaid = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        for (Loan loan : loans) {
            List<ScheduleLine> lines = database.scheduleLines(loan.id());
            borrowed = borrowed.add(borrowed(loan, lines));
            repaid = repaid.add(repaid(lines));
            BigDecimal loanRemaining = remaining(loan, lines);
            remaining = remaining.add(loanRemaining);
            rows.append("<tr><td><a href=\"/pret/").append(loan.id()).append("\">").append(escape(loan.name())).append("</a></td><td>").append(euro(loanRemaining)).append("</td></tr>");
        }
        String content = "<main class=\"shell\"><p><a class=\"back-link\" href=\"/\">Retour a la vue d'ensemble</a></p><section class=\"page-heading\"><div><p class=\"eyebrow\">BIEN</p><h1>" + escape(property.name()) + "<span>.</span></h1><p class=\"muted\">Achat le " + property.purchaseDate() + ".</p></div><a class=\"button\" href=\"/administration/bien/" + property.id() + "\">Administrer ce bien</a></section><section class=\"metrics\"><article class=\"metric\"><span>Cout total d'acquisition</span><strong>" + euro(property.purchase()) + "</strong></article><article class=\"metric\"><span>Valeur retenue</span><strong>" + euro(estimate(property)) + "</strong></article><article class=\"metric\"><span>Financement restant</span><strong>" + euro(remaining) + "</strong></article></section><section class=\"metrics\"><article class=\"metric\"><span>Montant emprunte</span><strong>" + euro(borrowed) + "</strong></article><article class=\"metric\"><span>Capital rembourse</span><strong>" + euro(repaid) + "</strong></article><article class=\"metric\"><span>Apports</span><strong>-</strong></article></section><section class=\"card section\"><h2>Financements</h2>" + (loans.isEmpty() ? "<p class=\"empty\">Aucun financement.</p>" : "<div class=\"table-wrap\"><table><thead><tr><th>Pret</th><th>Restant</th></tr></thead><tbody>" + rows + "</tbody></table></div>") + "</section></main>";
        return layout(property.name() + " · Patria", content, csrf);
    }

    static String propertyAdministration(Property property, String csrf) {
        return layout("Administration · " + property.name(), "<main class=\"shell admin-layout\"><aside class=\"admin-nav\"><p class=\"eyebrow\">ADMINISTRATION</p><a href=\"/administration\">Tous les biens</a><a class=\"active\" href=\"/administration/bien/" + property.id() + "\">Proprietes</a><a href=\"/bien/" + property.id() + "\">Consulter le bien</a></aside><section><p><a class=\"back-link\" href=\"/bien/" + property.id() + "\">Retour au bien</a></p><h1>Proprietes de " + escape(property.name()) + "<span>.</span></h1><section class=\"card section\"><h2>Gestion du bien</h2><p class=\"muted\">Les actions de modification et de financement seront regroupees ici.</p></section></section></main>", csrf);
    }

    static String administration(Database database, List<Property> properties, List<Loan> loans, String csrf, String error, String success) {
        StringBuilder propertyOptions = new StringBuilder();
        for (Property property : properties) {
            propertyOptions.append("<option value=\"").append(property.id()).append("\">").append(escape(property.name())).append("</option>");
        }
        StringBuilder loanRows = new StringBuilder();
        BigDecimal borrowed = BigDecimal.ZERO;
        BigDecimal repaid = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        for (Loan loan : loans) {
            List<ScheduleLine> lines = database.scheduleLines(loan.id());
            borrowed = borrowed.add(borrowed(loan, lines));
            repaid = repaid.add(repaid(lines));
            remaining = remaining.add(remaining(loan, lines));
            loanRows.append("<tr><td><a href=\"/pret/").append(loan.id()).append("\">").append(escape(loan.name())).append("</a></td><td>").append(escape(loan.propertyName())).append("</td><td>")
                    .append(euro(loan.referenceBalance())).append("</td><td>").append(loan.annualRate()).append(" %</td><td>")
                    .append(euro(loan.monthlyPayment().add(loan.monthlyInsurance()))).append("</td></tr>");
        }
        String notice = !error.isEmpty() ? "<p class=\"notice error\">" + escape(error) + "</p>" : success.isEmpty() ? "" : "<p class=\"notice success\">" + escape(success) + "</p>";
        String loanForm = properties.isEmpty() ? "<p class=\"empty\">Ajoutez d'abord un bien immobilier.</p>" : """
                <form method="post" action="/pret" enctype="multipart/form-data" class="stack"><input type="hidden" name="csrf" value="%s">
                <label>Bien finance <span class="required-mark">*</span><select name="property_id">%s</select></label><label>Nom du pret <span class="required-mark">*</span><input name="name" maxlength="100" required></label>
                <label>Fichier d'echeancier CSV <span class="required-mark">*</span><input name="csv" type="file" accept=".csv,text/csv" required></label><button type="submit">Ajouter le financement</button></form>
                """.formatted(csrf, propertyOptions);
        String loansTable = loans.isEmpty() ? "<p class=\"empty\">Aucun pret enregistre.</p>" : "<div class=\"table-wrap\"><table><thead><tr><th>Pret</th><th>Bien</th><th>Solde de reference</th><th>Taux</th><th>Mensualite</th></tr></thead><tbody>" + loanRows + "</tbody></table></div>";
        String content = """
                <main class="shell"><section class="page-heading"><div><p class="eyebrow">ADMINISTRATION</p><h1>Biens et prets<span>.</span></h1>
                <p class="muted">Ajoutez vos informations de reference. Les calculs detailles arriveront dans une prochaine version.</p></div></section>%s
                <section class="metrics"><article class="metric"><span>Emprunte</span><strong>%s</strong></article><article class="metric"><span>Rembourse</span><strong>%s</strong></article><article class="metric"><span>Restant</span><strong>%s</strong></article></section>
                <div class="columns"><section class="card section"><p class="eyebrow">AJOUTER UN BIEN</p><h2>Nouveau logement</h2>
                <form method="post" action="/bien" class="stack"><input type="hidden" name="csrf" value="%s"><label>Nom du bien <span class="required-mark">*</span><input name="name" maxlength="100" required></label>
                <label>Type<select name="kind"><option>Appartement</option><option>Maison</option></select></label><label>Commune<input name="commune" maxlength="100"></label>
                <label>Surface (m2)<input name="area" type="number" min="0" step="0.01"></label><div class="form-grid"><label>Cout total d'acquisition (EUR)<input name="purchase" type="number" min="0" step="0.01"></label><label>Date d'achat<input name="purchase_date" type="date"></label></div>
                <div class="form-grid"><label>Estimation (EUR)<input name="estimate" type="number" min="0" step="0.01"></label><label>Date d'estimation<input name="estimate_date" type="date"></label></div><button type="submit">Ajouter le bien</button></form></section>
                <section class="card section"><p class="eyebrow">AJOUTER UN PRET</p><h2>Nouveau financement</h2>%s</section></div>
                <section class="card section"><div class="section-title"><div><p class="eyebrow">VOS FINANCEMENTS</p><h2>Prets enregistres</h2></div><span class="count">%d</span></div>%s</section></main>
                """.formatted(notice, euro(borrowed), euro(repaid), euro(remaining), csrf, loanForm, loans.size(), loansTable);
        return layout("Administration · Patria", content, csrf);
    }

    static String loanDetail(Loan loan, List<ScheduleLine> lines, String csrf, String error, String success) {
        BigDecimal repaid = repaid(lines);
        BigDecimal remaining = remaining(loan, lines);
        LocalDate latest = lines.stream().map(ScheduleLine::date).max(LocalDate::compareTo).orElse(null);
        long months = latest == null ? 0 : Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), latest));
        String years = latest == null ? "Non disponible" : String.format(Locale.ROOT, "%.1f ans", months / 12.0);
        String notice = !error.isEmpty() ? "<p class=\"notice error\">" + escape(error) + "</p>" : success.isEmpty() ? "" : "<p class=\"notice success\">" + escape(success) + "</p>";
        StringBuilder rows = new StringBuilder();
        for (ScheduleLine line : lines) rows.append("<tr><td>").append(line.date()).append("</td><td>").append(escape(line.type()))
                .append("</td><td>").append(euroOrDash(line.amount())).append("</td><td>").append(euroOrDash(line.principalRepaid()))
                .append("</td><td>").append(euroOrDash(line.interest())).append("</td><td>").append(euroOrDash(line.fees()))
                .append("</td><td>").append(euroOrDash(line.remainingPrincipal())).append("</td><td>").append(numberOrDash(line.annualRate())).append("</td></tr>");
        String table = lines.isEmpty() ? "<p class=\"empty\">Aucune echeance importee.</p>" : "<div class=\"table-wrap\"><table><thead><tr><th>Date</th><th>Type</th><th>Montant</th><th>Capital rembourse</th><th>Interets</th><th>Frais</th><th>Capital restant du</th><th>Taux annuel</th></tr></thead><tbody>" + rows + "</tbody></table></div>";
        String content = """
                <main class="shell"><p><a class="back-link" href="/bien/%d">Retour au bien</a></p><section class="page-heading"><div><p class="eyebrow">CONSULTATION DU PRET</p><h1>%s<span>.</span></h1><p class="muted">Finance le bien %s.</p></div><a class="button" href="/pret/%d/echeancier">Gerer l'echeancier</a></section>%s
                <section class="metrics"><article class="metric"><span>Montant emprunte</span><strong>%s</strong></article><article class="metric"><span>Rembourse</span><strong>%s</strong></article><article class="metric"><span>Restant</span><strong>%s</strong></article><article class="metric"><span>Annees restantes</span><strong>%s</strong></article></section>
                <section class="card section"><div class="section-title"><div><p class="eyebrow">ECHEANCIER</p><h2>Lignes importees</h2></div><span class="count">%d</span></div>%s</section></main>
                """.formatted(loan.propertyId(), escape(loan.name()), escape(loan.propertyName()), loan.id(), notice, euro(borrowed(loan, lines)), euro(repaid), euro(remaining), years,
                lines.size(), table);
        return layout("Pret · " + loan.name() + " · Patria", content, csrf);
    }

    static String scheduleManagement(Loan loan, String csrf) {
        String content = "<main class=\"shell\"><p><a class=\"back-link\" href=\"/pret/" + loan.id() + "\">Retour au pret</a></p><section class=\"page-heading\"><div><p class=\"eyebrow\">ECHEANCIER</p><h1>" + escape(loan.name()) + "<span>.</span></h1></div></section><section class=\"card section\"><h2>Remplacer l'echeancier</h2><p class=\"muted\">Le nouvel import remplace toutes les lignes existantes.</p><form method=\"post\" action=\"/pret/" + loan.id() + "/echeancier\" enctype=\"multipart/form-data\" class=\"stack\"><input type=\"hidden\" name=\"csrf\" value=\"" + csrf + "\"><label>Fichier CSV <span class=\"required-mark\">*</span><input name=\"csv\" type=\"file\" accept=\".csv,text/csv\" required></label><button type=\"submit\">Importer</button></form></section></main>";
        return layout("Echeancier · " + loan.name(), content, csrf);
    }

    private static BigDecimal repaid(List<ScheduleLine> lines) {
        return lines.stream().filter(line -> !line.date().isAfter(LocalDate.now())).map(line -> line.principalRepaid() != null ? line.principalRepaid()
                : line.type().equals("REMBOURSEMENT_ANTICIPE") && line.amount() != null ? line.amount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal borrowed(Loan loan, List<ScheduleLine> lines) {
        BigDecimal drawdowns = lines.stream().filter(line -> line.type().equals("DEBLOCAGE")).map(ScheduleLine::amount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return drawdowns.signum() == 0 ? loan.initialAmount() : drawdowns;
    }

    private static BigDecimal remaining(Loan loan, List<ScheduleLine> lines) {
        BigDecimal balance = null;
        for (ScheduleLine line : lines) {
            if (line.date().isAfter(LocalDate.now())) break;
            if (line.remainingPrincipal() != null) balance = line.remainingPrincipal();
            else if (balance != null && line.principalRepaid() != null) balance = balance.subtract(line.principalRepaid()).max(BigDecimal.ZERO);
        }
        return balance == null ? loan.referenceBalance() : balance;
    }

    private static String euroOrDash(BigDecimal value) { return value == null ? "-" : euro(value); }
    private static String numberOrDash(BigDecimal value) { return value == null ? "-" : escape(value.stripTrailingZeros().toPlainString()) + " %"; }

    static String message(String title, String text) {
        return layout(title + " · Patria", "<main class=\"shell\"><section class=\"card section\"><h1>" + escape(title) + "</h1><p class=\"muted\">" + escape(text) + "</p></section></main>", null);
    }

    private static String layout(String title, String content, String csrf) {
        String header = csrf == null ? "" : "<header><div class=\"shell navigation\">" + brand() + "<nav><a href=\"/\">Vue d'ensemble</a><a href=\"/administration\">Administration</a></nav><form method=\"post\" action=\"/deconnexion\"><input type=\"hidden\" name=\"csrf\" value=\"" + csrf + "\"><button class=\"quiet\" type=\"submit\">Deconnexion</button></form></div></header>";
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + escape(title) + "</title><link rel=\"preload\" as=\"image\" href=\"/images/patria-logo.png\"><link rel=\"icon\" type=\"image/png\" href=\"/images/patria-logo.png\"><link rel=\"stylesheet\" href=\"/css/patria.css?v=3\"><script defer src=\"/js/table-sort.js?v=2\"></script></head><body>" + header + content + "</body></html>";
    }

    private static String brand() {
        return "<a class=\"brand\" href=\"/\"><img class=\"brand-mark\" src=\"/images/patria-logo.png\" width=\"31\" height=\"31\" decoding=\"sync\" alt=\"\"><span>Patria</span></a>";
    }

    private static String euro(BigDecimal value) {
        return escape(EURO.format(value));
    }

    private static BigDecimal estimate(Property property) {
        return property.estimate().signum() == 0 ? property.purchase() : property.estimate();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
