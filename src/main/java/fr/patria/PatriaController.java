package fr.patria;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PatriaController {
    private final PropertyRepository properties;
    private final LoanRepository loans;

    public PatriaController(PropertyRepository properties, LoanRepository loans) {
        this.properties = properties;
        this.loans = loans;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Property> propertyList = properties.findAll();
        List<Loan> loanList = loans.findAll();
        model.addAttribute("properties", propertyList);
        model.addAttribute("loans", loanList);
        model.addAttribute("propertyValue", propertyList.stream().map(Property::estimate).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("debt", loanList.stream().map(Loan::referenceBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
        return "dashboard";
    }

    @GetMapping("/administration")
    public String administration(Model model) {
        model.addAttribute("properties", properties.findAll());
        model.addAttribute("loans", loans.findAll());
        return "administration";
    }

    @GetMapping("/connexion")
    public String login() {
        return "login";
    }

    @PostMapping("/bien")
    public String addProperty(
            @RequestParam String name,
            @RequestParam String kind,
            @RequestParam String commune,
            @RequestParam String insee,
            @RequestParam String area,
            @RequestParam String purchase,
            @RequestParam("purchase_date") String purchaseDate,
            @RequestParam String estimate,
            @RequestParam("estimate_date") String estimateDate,
            RedirectAttributes redirect) {
        try {
            String normalizedKind = required(kind, "Type");
            if (!normalizedKind.equals("Appartement") && !normalizedKind.equals("Maison")) {
                throw new IllegalArgumentException("Type de bien invalide.");
            }
            String normalizedInsee = required(insee, "Code INSEE").toUpperCase(Locale.ROOT);
            if (!normalizedInsee.matches("[0-9A-Z]{5}")) {
                throw new IllegalArgumentException("Code INSEE invalide.");
            }
            LocalDate boughtOn = pastDate(purchaseDate, "Date d'achat");
            LocalDate estimatedOn = pastDate(estimateDate, "Date d'estimation");
            if (estimatedOn.isBefore(boughtOn)) {
                throw new IllegalArgumentException("L'estimation ne peut pas preceder l'achat.");
            }
            properties.insert(new Property(0, required(name, "Nom"), normalizedKind, required(commune, "Commune"),
                    normalizedInsee, positive(area, "Surface"), nonNegative(purchase, "Prix d'achat"), boughtOn,
                    nonNegative(estimate, "Estimation"), estimatedOn));
            redirect.addFlashAttribute("success", "Bien ajoute.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/administration";
    }

    @PostMapping("/pret")
    public String addLoan(
            @RequestParam("property_id") String propertyId,
            @RequestParam String name,
            @RequestParam String initial,
            @RequestParam("reference_balance") String referenceBalance,
            @RequestParam("reference_date") String referenceDate,
            @RequestParam String rate,
            @RequestParam("duration_months") String durationMonths,
            @RequestParam("start_date") String startDate,
            @RequestParam("due_day") String dueDay,
            @RequestParam String payment,
            @RequestParam String insurance,
            RedirectAttributes redirect) {
        try {
            long property = Long.parseLong(propertyId);
            if (!properties.exists(property)) {
                throw new IllegalArgumentException("Bien introuvable.");
            }
            BigDecimal initialAmount = positive(initial, "Capital initial");
            BigDecimal balance = nonNegative(referenceBalance, "Capital restant du");
            if (balance.compareTo(initialAmount) > 0) {
                throw new IllegalArgumentException("Le capital restant ne peut pas depasser le capital initial.");
            }
            int duration = boundedInteger(durationMonths, 1, 600, "Duree");
            int due = boundedInteger(dueDay, 1, 31, "Jour d'echeance");
            LocalDate startedOn = pastDate(startDate, "Date de debut");
            LocalDate referencedOn = pastDate(referenceDate, "Date de reference");
            if (referencedOn.isBefore(startedOn)) {
                throw new IllegalArgumentException("La date de reference doit suivre le debut du pret.");
            }
            loans.insert(new Loan(0, property, "", required(name, "Nom"), initialAmount, balance, referencedOn,
                    nonNegative(rate, "Taux"), duration, startedOn, due, positive(payment, "Mensualite"),
                    nonNegative(insurance, "Assurance")));
            redirect.addFlashAttribute("success", "Pret ajoute.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/administration";
    }

    private static String required(String value, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException(label + " invalide.");
        }
        return trimmed;
    }

    private static BigDecimal positive(String value, String label) {
        BigDecimal amount = nonNegative(value, label);
        if (amount.signum() == 0) {
            throw new IllegalArgumentException(label + " doit etre superieur a zero.");
        }
        return amount;
    }

    private static BigDecimal nonNegative(String value, String label) {
        try {
            BigDecimal amount = new BigDecimal(value.replace(',', '.'));
            if (amount.signum() < 0) {
                throw new IllegalArgumentException(label + " ne peut pas etre negatif.");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static int boundedInteger(String value, int min, int max, String label) {
        try {
            int result = Integer.parseInt(value);
            if (result < min || result > max) {
                throw new IllegalArgumentException(label + " invalide.");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " invalide.");
        }
    }

    private static LocalDate pastDate(String value, String label) {
        try {
            LocalDate result = LocalDate.parse(value);
            if (result.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException(label + " ne peut pas etre dans le futur.");
            }
            return result;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw exception;
            }
            throw new IllegalArgumentException(label + " invalide.");
        }
    }
}
