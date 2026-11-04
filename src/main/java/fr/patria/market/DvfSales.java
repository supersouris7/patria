package fr.patria.market;

import fr.patria.property.PropertyKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture d'un fichier DVF « geo-dvf » (une commune, une annee). Une mutation peut compter plusieurs lignes
 * (lots, dependances) : seules les ventes portant sur exactement un logement sont retenues, pour que le prix
 * se rapporte bien a la surface de ce logement.
 */
final class DvfSales {
    private DvfSales() {
    }

    static List<Sale> parse(String csv) {
        String[] lines = csv.split("\n");
        if (lines.length < 2) return List.of();
        Map<String, Integer> column = new HashMap<>();
        String[] header = split(lines[0].trim());
        for (int i = 0; i < header.length; i++) column.put(header[i], i);
        for (String required : List.of("id_mutation", "date_mutation", "nature_mutation", "valeur_fonciere", "type_local", "surface_reelle_bati", "latitude", "longitude")) {
            if (!column.containsKey(required)) throw new IllegalArgumentException("Colonne DVF manquante : " + required);
        }
        Map<String, List<String[]>> mutations = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] fields = split(lines[i]);
            if (fields.length != header.length) continue;
            mutations.computeIfAbsent(fields[column.get("id_mutation")], key -> new ArrayList<>()).add(fields);
        }
        List<Sale> sales = new ArrayList<>();
        for (List<String[]> rows : mutations.values()) {
            List<String[]> dwellings = rows.stream().filter(row -> kind(row[column.get("type_local")]) != null).toList();
            if (dwellings.size() != 1) continue;
            String[] row = dwellings.getFirst();
            if (!"Vente".equals(row[column.get("nature_mutation")])) continue;
            try {
                BigDecimal price = new BigDecimal(row[column.get("valeur_fonciere")]);
                BigDecimal area = new BigDecimal(row[column.get("surface_reelle_bati")]);
                if (price.signum() <= 0 || area.signum() <= 0) continue;
                sales.add(new Sale(LocalDate.parse(row[column.get("date_mutation")]), price, area, kind(row[column.get("type_local")]),
                        Double.parseDouble(row[column.get("latitude")]), Double.parseDouble(row[column.get("longitude")]), address(row, column)));
            } catch (RuntimeException ignored) {
                // ligne incomplete (coordonnees ou montant absents) : ignoree
            }
        }
        return sales;
    }

    /** Adresse lisible (« 12 B rue des Lilas »), vide si les colonnes d'adresse manquent. */
    private static String address(String[] row, Map<String, Integer> column) {
        List<String> parts = new ArrayList<>();
        for (String name : List.of("adresse_numero", "adresse_suffixe", "adresse_nom_voie")) {
            Integer index = column.get(name);
            if (index != null && !row[index].isBlank()) parts.add(row[index].trim());
        }
        return String.join(" ", parts);
    }

    /** Decoupe une ligne CSV en respectant les champs entre guillemets. */
    static String[] split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c != '\r') {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(String[]::new);
    }

    private static PropertyKind kind(String type) {
        return switch (type) {
            case "Appartement" -> PropertyKind.APARTMENT;
            case "Maison" -> PropertyKind.HOUSE;
            default -> null;
        };
    }
}
