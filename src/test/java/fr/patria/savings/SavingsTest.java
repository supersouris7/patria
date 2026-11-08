package fr.patria.savings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.Frequency;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SavingsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static SavingsRepository.Account account(long id, long person, SavingsKind kind, String balance) {
        return new SavingsRepository.Account(id, person, kind, kind.label(), new BigDecimal(balance), TODAY, null, null);
    }

    @Test
    void parsesYahooQuotesAndInseeInflation() {
        MarketFeeds.Quote quote = MarketFeeds.parseQuote("{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"EUR\",\"symbol\":\"AI.PA\",\"regularMarketPrice\":187.78}}]}}");
        assertEquals(new BigDecimal("187.78"), quote.price());
        assertEquals("EUR", quote.currency());
        String xml = "<Series IDBANK=\"011814630\"><Obs TIME_PERIOD=\"2026-10\" OBS_VALUE=\"103.47\" OBS_STATUS=\"P\"/>"
                + "<Obs TIME_PERIOD=\"2026-09\" OBS_VALUE=\"103.2\"/><Obs TIME_PERIOD=\"2025-10\" OBS_VALUE=\"100.06\" OBS_STATUS=\"A\"/></Series>";
        MarketFeeds.Inflation inflation = MarketFeeds.parseInflation(xml);
        assertEquals(new BigDecimal("3.4"), inflation.percent(), "103,47 / 100,06 − 1");
        assertEquals(YearMonth.of(2026, 10), inflation.month());
    }

    @Test
    void feedsAreFetchedInBackgroundAndNeverOffline() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);
        MarketFeeds online = new MarketFeeds(url -> url.contains("yahoo") ? "{\"currency\":\"EUR\",\"regularMarketPrice\":10.5}"
                : "<Obs TIME_PERIOD=\"2026-10\" OBS_VALUE=\"102\"/><Obs TIME_PERIOD=\"2025-10\" OBS_VALUE=\"100\"/>", clock, Runnable::run, false);
        assertEquals(new BigDecimal("10.5"), online.quote("cw8.pa").orElseThrow().price());
        assertEquals(new BigDecimal("2.0"), online.inflation().orElseThrow().percent());
        MarketFeeds offline = new MarketFeeds(url -> { throw new AssertionError("aucun appel hors ligne"); }, clock, Runnable::run, true);
        assertTrue(offline.quote("AI.PA").isEmpty() && offline.inflation().isEmpty());
        assertTrue(online.quote("bad symbol!").isEmpty(), "code refusé avant tout appel");
    }

    @Test
    void positionsUseTheLiveQuoteThenTheManualPriceThenTheCost() {
        SavingsRepository.Position position = new SavingsRepository.Position(1, 1, "AI.PA", "Air Liquide", new BigDecimal("10"), new BigDecimal("1500"), null);
        assertEquals(new BigDecimal("1877.80"), Savings.value(position, Optional.of(new MarketFeeds.Quote(new BigDecimal("187.78"), "EUR"))).value());
        assertEquals("prix de revient", Savings.value(position, Optional.empty()).priceSource());
        assertTrue(Savings.value(position, Optional.of(new MarketFeeds.Quote(new BigDecimal("50"), "USD"))).priceSource().contains("USD"), "pas de conversion de devise");
    }

    @Test
    void planFillsTheLepThenLivretAThenLdds() {
        List<SavingsRepository.Account> accounts = List.of(account(1, 1, SavingsKind.CURRENT, "1000"), account(2, 1, SavingsKind.LIVRET_A, "22950"));
        Savings.Overview overview = Savings.overview(accounts, List.of(), Map.of(1L, "Paul"), symbol -> Optional.empty(), TODAY);
        Savings.Plan plan = Savings.plan(new BigDecimal("2000"), new BigDecimal("600"), overview, List.of(1L), 6, "", TODAY);
        assertEquals(new BigDecimal("12000"), plan.target());
        assertEquals(new BigDecimal("23950"), plan.available());
        assertEquals(BigDecimal.ZERO, plan.missing(), "objectif atteint");

        Savings.Overview small = Savings.overview(List.of(account(1, 1, SavingsKind.CURRENT, "500")), List.of(), Map.of(1L, "Paul"), symbol -> Optional.empty(), TODAY);
        Savings.Plan start = Savings.plan(new BigDecimal("2000"), new BigDecimal("600"), small, List.of(1L), 6, "oui", TODAY);
        assertEquals(new BigDecimal("11500"), start.missing());
        assertEquals(new BigDecimal("300"), start.monthly(), "au plus la moitié du reste mensuel");
        assertEquals("LEP", start.steps().getFirst().where());
        assertEquals(new BigDecimal("10000"), start.steps().getFirst().amount(), "plafond du LEP");
        assertEquals("Livret A", start.steps().get(1).where());
        assertEquals(new BigDecimal("1500"), start.steps().get(1).amount());
        assertTrue(Savings.plan(new BigDecimal("2000"), BigDecimal.ZERO, small, List.of(1L), 6, "non", TODAY).steps().stream().noneMatch(step -> step.where().equals("LEP")));
    }

    @Test
    void purchasingPowerComparesIncomeGrowthWithInflation() {
        List<Income> incomes = List.of(
                new Income(1, 1, "Salaire", IncomeKind.SALARY, new BigDecimal("2000"), Frequency.MONTHLY, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 3, 31)),
                new Income(2, 1, "Salaire", IncomeKind.SALARY, new BigDecimal("2100"), Frequency.MONTHLY, LocalDate.of(2026, 4, 1), null));
        Savings.PurchasingPower power = Savings.purchasingPower(incomes, TODAY, Optional.of(new MarketFeeds.Inflation(new BigDecimal("3.4"), YearMonth.of(2026, 10))))
                .orElseThrow();
        assertEquals(new BigDecimal("5.0"), power.income());
        assertEquals(new BigDecimal("1.5"), power.power(), "1,05 / 1,034 − 1");
        assertTrue(Savings.purchasingPower(incomes, TODAY, Optional.empty()).isEmpty());
    }

    @Test
    void regulatedRatesAreDated() {
        assertEquals(new BigDecimal("1.7"), RegulatedRates.at(SavingsKind.LIVRET_A, TODAY).orElseThrow().percent());
        assertEquals(new BigDecimal("2.5"), RegulatedRates.at(SavingsKind.LEP, TODAY).orElseThrow().percent());
        assertEquals(new BigDecimal("2.7"), RegulatedRates.before(SavingsKind.LEP, TODAY).orElseThrow().percent());
        assertTrue(RegulatedRates.at(SavingsKind.LIFE_INSURANCE, TODAY).isEmpty());
    }
}
