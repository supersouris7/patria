package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.TestData;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HousingRatesTest {
    private static final String CSV = """
            KEY,FREQ,REF_AREA,BS_REP_SECTOR,BS_ITEM,MATURITY_NOT_IRATE,DATA_TYPE_MIR,AMOUNT_CAT,BS_COUNT_SECTOR,CURRENCY_TRANS,IR_BUS_COV,TIME_PERIOD,OBS_VALUE,OBS_STATUS
            MIR.M.FR.B.A2C.P.R.A.2250.EUR.N,M,FR,B,A2C,P,R,A,2250,EUR,N,2026-09,3.19,A
            """;

    @Test
    void readsTheLastObservation() {
        HousingRate rate = HousingRates.parse(CSV);
        assertEquals(0, new BigDecimal("3.19").compareTo(rate.rate()));
        assertEquals(YearMonth.of(2026, 9), rate.month());
        assertThrows(IllegalArgumentException.class, () -> HousingRates.parse("KEY,TIME_PERIOD,OBS_VALUE\nX,2026-09,95\n"), "taux invraisemblable");
        assertThrows(IllegalArgumentException.class, () -> HousingRates.parse("KEY\n"), "réponse vide");
    }

    @Test
    void fetchedInBackgroundAndKeptWhenTheSourceFails() {
        AtomicReference<String> response = new AtomicReference<>(CSV);
        HousingRates rates = new HousingRates(() -> {
            if (response.get() == null) throw new IOException("BCE injoignable");
            return response.get();
        }, TestData.CLOCK, Runnable::run);
        assertTrue(rates.current().isPresent(), "téléchargé dès la première demande (ici de façon synchrone)");
        response.set(null);
        assertEquals(0, new BigDecimal("3.19").compareTo(rates.current().orElseThrow().rate()), "panne : le dernier taux connu reste");
    }
}
