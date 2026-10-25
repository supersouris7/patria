package fr.patria;

import java.math.BigDecimal;
import java.text.NumberFormat;
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
                <title>Connexion · Patria</title><link rel="icon" type="image/png" href="/images/patria-logo.png"><link rel="stylesheet" href="/css/patria.css"></head>
                <body class="login-page"><main class="login-card">%s<h1>Connexion</h1>
                <p class="muted">Vos donnees restent sur votre serveur.</p>%s<form method="post" action="/connexion" class="stack">
                <label>Mot de passe<input name="password" type="password" autocomplete="current-password" required autofocus></label><button type="submit">Ouvrir Patria</button>
                </form></main></body></html>
                """.formatted(brand(), notice);
    }

    static String dashboard(List<Property> properties, List<Loan> loans, String csrf) {
        BigDecimal value = properties.stream().map(Property::estimate).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal debt = loans.stream().map(Loan::referenceBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        StringBuilder rows = new StringBuilder();
        for (Property property : properties) {
            rows.append("<tr><td><strong>").append(escape(property.name())).append("</strong><small>").append(escape(property.kind()))
                    .append("</small></td><td>").append(escape(property.commune())).append("</td><td>").append(property.area())
                    .append(" m2</td><td>").append(euro(property.estimate())).append("</td></tr>");
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

    static String administration(List<Property> properties, List<Loan> loans, String csrf, String error, String success) {
        StringBuilder propertyOptions = new StringBuilder();
        for (Property property : properties) {
            propertyOptions.append("<option value=\"").append(property.id()).append("\">").append(escape(property.name())).append("</option>");
        }
        StringBuilder loanRows = new StringBuilder();
        for (Loan loan : loans) {
            loanRows.append("<tr><td>").append(escape(loan.name())).append("</td><td>").append(escape(loan.propertyName())).append("</td><td>")
                    .append(euro(loan.referenceBalance())).append("</td><td>").append(loan.annualRate()).append(" %</td><td>")
                    .append(euro(loan.monthlyPayment().add(loan.monthlyInsurance()))).append("</td></tr>");
        }
        String notice = !error.isEmpty() ? "<p class=\"notice error\">" + escape(error) + "</p>" : success.isEmpty() ? "" : "<p class=\"notice success\">" + escape(success) + "</p>";
        String loanForm = properties.isEmpty() ? "<p class=\"empty\">Ajoutez d'abord un bien immobilier.</p>" : """
                <form method="post" action="/pret" class="stack"><input type="hidden" name="csrf" value="%s">
                <label>Bien finance<select name="property_id">%s</select></label><label>Nom du pret<input name="name" maxlength="100" required></label>
                <div class="form-grid"><label>Capital initial (EUR)<input name="initial" type="number" min="0.01" step="0.01" required></label><label>Capital restant du (EUR)<input name="reference_balance" type="number" min="0" step="0.01" required></label></div>
                <label>Date du capital de reference<input name="reference_date" type="date" required></label><div class="form-grid"><label>Taux annuel (%%)<input name="rate" type="number" min="0" step="0.001" required></label><label>Duree (mois)<input name="duration_months" type="number" min="1" max="600" required></label></div>
                <div class="form-grid"><label>Debut du pret<input name="start_date" type="date" required></label><label>Jour d'echeance<input name="due_day" type="number" min="1" max="31" required></label></div>
                <div class="form-grid"><label>Mensualite hors assurance (EUR)<input name="payment" type="number" min="0.01" step="0.01" required></label><label>Assurance mensuelle (EUR)<input name="insurance" type="number" min="0" step="0.01" value="0" required></label></div><button type="submit">Ajouter le pret</button></form>
                """.formatted(csrf, propertyOptions);
        String loansTable = loans.isEmpty() ? "<p class=\"empty\">Aucun pret enregistre.</p>" : "<div class=\"table-wrap\"><table><thead><tr><th>Pret</th><th>Bien</th><th>Solde de reference</th><th>Taux</th><th>Mensualite</th></tr></thead><tbody>" + loanRows + "</tbody></table></div>";
        String content = """
                <main class="shell"><section class="page-heading"><div><p class="eyebrow">ADMINISTRATION</p><h1>Biens et prets<span>.</span></h1>
                <p class="muted">Ajoutez vos informations de reference. Les calculs detailles arriveront dans une prochaine version.</p></div></section>%s
                <div class="columns"><section class="card section"><p class="eyebrow">AJOUTER UN BIEN</p><h2>Nouveau logement</h2>
                <form method="post" action="/bien" class="stack"><input type="hidden" name="csrf" value="%s"><label>Nom du bien <span class="required-mark">*</span><input name="name" maxlength="100" required></label>
                <label>Type<select name="kind"><option>Appartement</option><option>Maison</option></select></label><label>Commune<input name="commune" maxlength="100"></label>
                <label>Surface (m2)<input name="area" type="number" min="0" step="0.01"></label><div class="form-grid"><label>Prix d'achat (EUR)<input name="purchase" type="number" min="0" step="0.01"></label><label>Date d'achat<input name="purchase_date" type="date"></label></div>
                <div class="form-grid"><label>Estimation (EUR)<input name="estimate" type="number" min="0" step="0.01"></label><label>Date d'estimation<input name="estimate_date" type="date"></label></div><button type="submit">Ajouter le bien</button></form></section>
                <section class="card section"><p class="eyebrow">AJOUTER UN PRET</p><h2>Nouveau financement</h2>%s</section></div>
                <section class="card section"><div class="section-title"><div><p class="eyebrow">VOS FINANCEMENTS</p><h2>Prets enregistres</h2></div><span class="count">%d</span></div>%s</section></main>
                """.formatted(notice, csrf, loanForm, loans.size(), loansTable);
        return layout("Administration · Patria", content, csrf);
    }

    static String message(String title, String text) {
        return layout(title + " · Patria", "<main class=\"shell\"><section class=\"card section\"><h1>" + escape(title) + "</h1><p class=\"muted\">" + escape(text) + "</p></section></main>", null);
    }

    private static String layout(String title, String content, String csrf) {
        String header = csrf == null ? "" : "<header><div class=\"shell navigation\">" + brand() + "<nav><a href=\"/\">Vue d'ensemble</a><a href=\"/administration\">Administration</a></nav><form method=\"post\" action=\"/deconnexion\"><input type=\"hidden\" name=\"csrf\" value=\"" + csrf + "\"><button class=\"quiet\" type=\"submit\">Deconnexion</button></form></div></header>";
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + escape(title) + "</title><link rel=\"icon\" type=\"image/png\" href=\"/images/patria-logo.png\"><link rel=\"stylesheet\" href=\"/css/patria.css\"></head><body>" + header + content + "</body></html>";
    }

    private static String brand() {
        return "<a class=\"brand\" href=\"/\"><img class=\"brand-mark\" src=\"/images/patria-logo.png\" alt=\"\"><span>Patria</span></a>";
    }

    private static String euro(BigDecimal value) {
        return escape(EURO.format(value));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
