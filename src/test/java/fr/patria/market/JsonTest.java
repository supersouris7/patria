package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesDataGouvResponses() {
        Map<String, Object> value = Json.object(Json.parse("{\"features\": [{\"coordinates\": [-1.2, 44.6e0], \"label\": \"Rue du D\\u00e9barcad\\u00e8re\", \"ok\": true, \"none\": null}]}"));
        Map<String, Object> feature = Json.object(Json.array(value.get("features")).getFirst());

        assertEquals(List.of(new BigDecimal("-1.2"), new BigDecimal("44.6e0")), feature.get("coordinates"));
        assertEquals("Rue du Débarcadère", feature.get("label"));
        assertEquals(Boolean.TRUE, feature.get("ok"));
        assertEquals(null, feature.get("none"));
    }
}
