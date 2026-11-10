// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.property;

import fr.patria.common.ValidationException;
import java.util.Arrays;

/** Type de bien. Le nom de la constante est stocke en base, le libelle est affiche. */
public enum PropertyKind {
    APARTMENT("Appartement"),
    HOUSE("Maison");

    private final String label;

    PropertyKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PropertyKind parse(String value) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Type de bien invalide."));
    }
}
