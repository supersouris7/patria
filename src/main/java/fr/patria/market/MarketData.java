package fr.patria.market;

import fr.patria.property.PropertyKind;
import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** Sources de donnees publiques. Interface pour pouvoir tester le calcul sans reseau. */
interface MarketData {
    /** Geolocalise une adresse (ou a defaut la commune). Vide si introuvable. */
    Optional<MarketAnalysis.Location> locate(String address, String commune) throws IOException;

    /** Fichier DVF d'une commune pour une annee ; vide si l'annee ou la commune n'est pas publiee. */
    Optional<String> sales(String citycode, int year) throws IOException;

    /** Medianes mensuelles du prix au m² dans un departement pour un type de bien, a partir du mois {@code from}. */
    List<PriceIndex.Point> departmentIndex(String department, PropertyKind kind, YearMonth from) throws IOException;
}
