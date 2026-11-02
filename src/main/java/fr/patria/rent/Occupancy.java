package fr.patria.rent;

import fr.patria.common.ValidationException;
import java.util.Arrays;

/** Usage du bien pendant une periode. Le nom de la constante est stocke en base. */
public enum Occupancy {
    /** Bien loue : le loyer est percu. */
    RENTAL("Location", "Loyer perçu"),
    /** Residence principale : le loyer de reference est le loyer economise (ce qu'un logement equivalent couterait). */
    OWNER("Résidence principale", "Loyer de référence");

    private final String label;
    private final String amountLabel;

    Occupancy(String label, String amountLabel) {
        this.label = label;
        this.amountLabel = amountLabel;
    }

    public String label() {
        return label;
    }

    public String amountLabel() {
        return amountLabel;
    }

    public static Occupancy parse(String value) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Type d'occupation invalide."));
    }
}
