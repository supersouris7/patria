package fr.patria.property;

import fr.patria.common.ValidationException;
import fr.patria.ui.Format;
import fr.patria.web.FormData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Conversion entre le formulaire d'un bien et un {@link Property} valide. */
final class PropertyForm {
    private PropertyForm() {
    }

    /** Lit et valide la saisie. {@code current} vaut {@code null} a la creation. */
    static Property read(FormData form, Property current, LocalDate today) {
        LocalDate purchaseDate = form.pastDate("purchase_date", "Date d'acquisition", today, current == null ? today : current.purchaseDate());
        LocalDate estimateDate = form.pastDate("estimate_date", "Date d'estimation", today, purchaseDate);
        if (estimateDate.isBefore(purchaseDate)) throw new ValidationException("La date d'estimation ne peut pas précéder l'acquisition.");
        BigDecimal low = positiveOrNull(form.optionalAmount("estimate", "Estimation prudente"));
        BigDecimal high = positiveOrNull(form.optionalAmount("estimate_high", "Estimation optimiste"));
        if (low != null && high != null && high.compareTo(low) < 0) {
            throw new ValidationException("L'estimation optimiste doit être supérieure ou égale à l'estimation prudente.");
        }
        BigDecimal purchase = form.optionalAmount("purchase", "Coût d'acquisition");
        BigDecimal recurring = form.optionalAmount("recurring_fees", "Frais récurrents annuels");
        Property property = new Property(current == null ? 0 : current.id(),
                form.requiredText("name", "Nom du bien", 100), PropertyKind.parse(form.raw("kind")),
                form.optionalText("commune", "Commune", 100), form.optionalText("address", "Adresse", 200),
                positiveOrNull(form.optionalAmount("area", "Surface")), purchase == null ? BigDecimal.ZERO : purchase, purchaseDate,
                low, high, estimateDate, form.percent("sale_fees_pct", "Frais de vente et fiscalité", Valuation.DEFAULT_SALE_FEES),
                recurring == null ? BigDecimal.ZERO : recurring, null);
        // La valeur retenue reste valable tant que les scenarios du bien ne changent pas.
        boolean keepSelection = current != null && Valuation.scenarios(property).sameAs(Valuation.scenarios(current));
        return property.withSelectedValue(keepSelection ? current.selectedValue() : null);
    }

    static Map<String, String> values(Property property) {
        Map<String, String> values = new HashMap<>();
        values.put("name", property.name());
        values.put("kind", property.kind().name());
        values.put("commune", property.commune());
        values.put("address", property.address());
        values.put("area", Format.plain(property.area()));
        values.put("purchase", Format.plain(property.purchase()));
        values.put("purchase_date", property.purchaseDate().toString());
        values.put("estimate", Format.plain(property.estimateLow()));
        values.put("estimate_high", Format.plain(property.estimateHigh()));
        values.put("estimate_date", property.estimateDate().toString());
        values.put("sale_fees_pct", Format.plain(property.saleFeesRate().multiply(BigDecimal.valueOf(100))));
        values.put("recurring_fees", Format.plain(property.recurringFees()));
        return values;
    }

    static Map<String, String> defaults(LocalDate today) {
        return Map.of("kind", PropertyKind.APARTMENT.name(), "purchase_date", today.toString(), "sale_fees_pct", "8");
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }
}
