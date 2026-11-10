// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import fr.patria.common.Frequency;
import java.util.List;

/** Depenses courantes proposees en un clic dans le formulaire de saisie. */
record Preset(String label, Category category, Frequency frequency) {
    static final List<Preset> ALL = List.of(
            new Preset("Loyer", Category.HOUSING, Frequency.MONTHLY),
            new Preset("Électricité", Category.ENERGY, Frequency.MONTHLY),
            new Preset("Gaz", Category.ENERGY, Frequency.MONTHLY),
            new Preset("Eau", Category.ENERGY, Frequency.SEMIANNUAL),
            new Preset("Internet", Category.TELECOM, Frequency.MONTHLY),
            new Preset("Téléphone mobile", Category.TELECOM, Frequency.MONTHLY),
            new Preset("Mutuelle", Category.HEALTH, Frequency.MONTHLY),
            new Preset("Assurance auto", Category.INSURANCE, Frequency.ANNUAL),
            new Preset("Assurance habitation", Category.INSURANCE, Frequency.ANNUAL),
            new Preset("Impôt sur le revenu (prélèvement à la source)", Category.TAXES, Frequency.MONTHLY),
            new Preset("Crédit auto", Category.CREDIT, Frequency.MONTHLY),
            new Preset("Carburant", Category.TRANSPORT, Frequency.MONTHLY),
            new Preset("Crèche, cantine", Category.CHILDREN, Frequency.MONTHLY),
            new Preset("Streaming, abonnements", Category.TELECOM, Frequency.MONTHLY));
}
