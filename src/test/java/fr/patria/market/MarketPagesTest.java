// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarketPagesTest {
    @Test
    void mapLinksPointToTheSaleCoordinates() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=45.775840,4.833730", MarketPages.googleMaps(45.77584, 4.83373));
        assertEquals("https://explore.data.gouv.fr/fr/immobilier?onglet=carte&filtre=tous&lat=45.77584&lng=4.83373&zoom=18.0", MarketPages.dvfMap(45.77584, 4.83373, 18));
    }
}
