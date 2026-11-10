// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.patria.property.PropertyKind;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DvfSalesTest {
    private static final String HEADER = "id_mutation,date_mutation,numero_disposition,nature_mutation,valeur_fonciere,adresse_nom_voie,type_local,surface_reelle_bati,longitude,latitude";

    @Test
    void keepsSingleDwellingSalesWithTheirOutbuildings() {
        String csv = HEADER + "\n"
                + "A,2024-01-09,1,Vente,300000,\"RUE DU PORT, BAT B\",Appartement,60,-1.2,44.6\n"
                + "A,2024-01-09,1,Vente,300000,RUE DU PORT,Dépendance,,-1.2,44.6\n"
                + "B,2024-02-01,1,Vente,900000,RUE X,Appartement,50,-1.2,44.6\n"
                + "B,2024-02-01,1,Vente,900000,RUE X,Appartement,55,-1.2,44.6\n"
                + "C,2024-03-01,1,Echange,200000,RUE Y,Maison,100,-1.2,44.6\n"
                + "D,2024-04-01,1,Vente,450000,RUE Z,Maison,90,-1.21,44.61\n";

        List<Sale> sales = DvfSales.parse(csv);

        assertEquals(2, sales.size(), "vente multi-logements et échange écartés");
        assertEquals(PropertyKind.APARTMENT, sales.getFirst().kind());
        assertEquals(new BigDecimal("5000.00"), sales.getFirst().pricePerSquareMeter(), "virgule entre guillemets gérée");
        assertEquals(PropertyKind.HOUSE, sales.getLast().kind());
    }
}
