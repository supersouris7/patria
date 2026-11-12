// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
        Savings.PurchasingPower constant = Savings.purchasingPower(List.of(incomes.get(1)), TODAY,
                Optional.of(new MarketFeeds.Inflation(new BigDecimal("3.4"), YearMonth.of(2026, 10)))).orElseThrow();
        assertTrue(constant.estimated(), "sans revenu d'il y a un an : revenus supposés constants");
        assertEquals(new BigDecimal("-3.3"), constant.power(), "1 / 1,034 − 1");
    }

    @Test
    void regulatedRatesAreDated() {
        assertEquals(new BigDecimal("1.7"), RegulatedRates.at(SavingsKind.LIVRET_A, TODAY).orElseThrow().percent());
        assertEquals(new BigDecimal("2.5"), RegulatedRates.at(SavingsKind.LEP, TODAY).orElseThrow().percent());
        assertEquals(new BigDecimal("2.7"), RegulatedRates.before(SavingsKind.LEP, TODAY).orElseThrow().percent());
        assertTrue(RegulatedRates.at(SavingsKind.LIFE_INSURANCE, TODAY).isEmpty());
    }

    @Test
    void scheduledContributionsAdvanceTheBalanceUpToTheCeiling() {
        SavingsRepository.Account livret = new SavingsRepository.Account(1, 1, SavingsKind.LIVRET_A, "Livret A", new BigDecimal("22000"), LocalDate.of(2026, 8, 20), null, null,
                new SavingsRepository.Contribution(new BigDecimal("200"), Frequency.MONTHLY, LocalDate.of(2026, 1, 5), 9L));
        assertEquals(new BigDecimal("22600"), Savings.balance(livret, TODAY), "septembre, octobre, novembre (le 5 est passé)");
        assertEquals(new BigDecimal("22400"), Savings.balance(livret, LocalDate.of(2026, 11, 4)), "pas encore celui de novembre");
        SavingsRepository.Account full = new SavingsRepository.Account(2, 1, SavingsKind.LIVRET_A, "Livret A", new BigDecimal("22900"), LocalDate.of(2026, 8, 20), null, null,
                livret.contribution());
        assertEquals(new BigDecimal("22950"), Savings.balance(full, TODAY), "plafond du Livret A");
    }

    @Test
    void positionsImportResolvesIsinAndSkipsDuplicates() {
        List<PositionCsv.Row> rows = PositionCsv.parse("IE00B4L5Y983;ETF Monde;12,4;1050,30\nAI.PA;Air Liquide;5;780\nAI.PA;Doublon;1;100\nXX;;0;1\n",
                List.of(), isin -> isin.equals("IE00B4L5Y983") ? Optional.of("EUNL.DE") : Optional.empty());
        assertEquals("EUNL.DE", rows.get(0).symbol());
        assertEquals(new BigDecimal("12.4"), rows.get(0).quantity());
        assertTrue(rows.get(1).fresh());
        assertTrue(rows.get(2).duplicate(), "même code deux fois");
        assertTrue(rows.get(3).error().startsWith("Quantité"));
    }

    @Test
    void brokerExportsAreReadWithoutAi() {
        // Revolut (actions) : achats, vente partielle, dividende ignore
        String revolut = """
                Date,Ticker,Type,Quantity,Price per share,Total Amount,Currency,FX Rate
                2026-01-02T10:00:00Z,AAPL,BUY - MARKET,2,USD 150,USD 300,USD,1.1
                2026-02-02T10:00:00Z,AAPL,BUY - MARKET,2,USD 200,USD 400,USD,1.1
                2026-03-02T10:00:00Z,AAPL,SELL - MARKET,1,USD 210,USD 210,USD,1.1
                2026-03-05T10:00:00Z,AAPL,DIVIDEND,,,USD 1.20,USD,1.1
                """;
        assertEquals(Optional.of("AAPL;AAPL;3;525.00"), BrokerExport.toLines(revolut));
        // Degiro (transactions) : point-virgule, virgule decimale, vente en quantite negative, frais comptes
        String degiro = """
                Date;Heure;Produit;Code ISIN;Quantité;Cours;Montant;Frais de courtage
                02-01-2026;09:00;ISHARES CORE MSCI WORLD;IE00B4L5Y983;10;100,00;-1000,00;-2,00
                03-02-2026;09:00;ISHARES CORE MSCI WORLD;IE00B4L5Y983;-5;110,00;550,00;-2,00
                """;
        assertEquals(Optional.of("IE00B4L5Y983;ISHARES CORE MSCI WORLD;5;501.00"), BrokerExport.toLines(degiro));
        // Coinbase : lignes d'introduction, crypto cotee en euros, staking compte, envoi ignore
        String coinbase = """
                Transactions
                User,Paul
                Timestamp,Transaction Type,Asset,Quantity Transacted,Spot Price Currency,Subtotal,Fees
                2026-01-01,Buy,BTC,"0,01",EUR,"400,00","4,00"
                2026-01-02,Staking Income,ETH,0.5,EUR,0,0
                2026-01-03,Send,BTC,0.005,EUR,0,0
                """;
        List<PositionCsv.Row> rows = PositionCsv.parse(BrokerExport.toLines(coinbase).orElseThrow(), List.of(
                new SavingsRepository.Position(7, 1, "BTC-EUR", "Bitcoin", BigDecimal.ONE, BigDecimal.TEN, null)), code -> Optional.empty());
        assertEquals("BTC-EUR", rows.get(0).symbol());
        assertEquals(7L, rows.get(0).existing(), "ligne deja presente : mise a jour");
        assertEquals(new BigDecimal("404.00"), rows.get(0).cost());
        assertEquals("ETH-EUR", rows.get(1).symbol());
        assertTrue(BrokerExport.toLines("IE00B4L5Y983;ETF Monde;12,4;1050,30").isEmpty(), "le format Patria passe tel quel");
        assertEquals("BRK-B", BrokerExport.yahoo("BRK-B"));
    }

    @Test
    void isinPrefersAEuroListing() {
        MarketFeeds feeds = new MarketFeeds(url -> "{\"quotes\":[{\"symbol\":\"VWRA.L\"},{\"symbol\":\"VWCE.DE\"},{\"symbol\":\"VWCE.MI\"}]}",
                Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC), Runnable::run, false);
        assertEquals(Optional.of("VWCE.DE"), feeds.resolve("IE00BK5BQT80"));
    }

    @Test
    void tradeRepublicExportGivesTitlesAndCash() {
        // Format natif de Trade Republic (2026) : virements, achats, dividende, recalcul d'impot dans un seul fichier
        String export = """
                "datetime","date","account_type","category","type","asset_class","name","symbol","shares","price","amount","fee","tax","currency","original_amount","original_currency","fx_rate","description","transaction_id","counterparty_name","counterparty_iban","payment_reference","mcc_code"
                "2026-01-05T10:30:20Z","2026-01-05","DEFAULT","CASH","TRANSFER_INSTANT_INBOUND","","","","","","300.000000","","","EUR","","","","Incoming transfer","a1","","","",""
                "2026-01-05T10:31:23Z","2026-01-05","DEFAULT","TRADING","BUY","FUND","Core MSCI World USD (Acc)","IE000BI8OT95","2.0000000000","100.000000","-200.00","-1.00","","EUR","","","","Buy trade","a2","","","",""
                "2026-01-05T13:42:05Z","2026-01-05","DEFAULT","TRADING","BUY","CRYPTO","Bitcoin","BTC","0.0010000000","50000.000000","-50.00","-1.00","","EUR","","","","Buy trade","a3","","","",""
                "2026-03-16T19:26:49Z","2026-03-16","DEFAULT","CASH","DIVIDEND","FUND","NASDAQ100 USD (Dist)","DE000A0F5UF5","1.0000000000","","0.100000","","-0.02","EUR","0.11","USD","0.87","Cash Dividend","a4","","","",""
                """;
        assertEquals(Optional.of("IE000BI8OT95;Core MSCI World USD (Acc);2;201.00\nBTC;Bitcoin;0.001;51.00"), BrokerExport.toLines(export), "dividende sans achat ignoré");
        assertEquals(Optional.of(new BigDecimal("48.08")), BrokerExport.cash(export), "300 − 201 − 51 + 0,10 − 0,02");
        assertTrue(BrokerExport.cash("Date,Ticker,Type,Quantity\n2026-01-02,AAPL,BUY,2").isEmpty(), "sans les mouvements d'argent, pas d'espèces devinées");
        List<BrokerExport.Movement> movements = BrokerExport.movements(export);
        assertEquals(2, movements.size(), "achats seulement : ni virement ni dividende");
        assertEquals(java.time.LocalDate.of(2026, 1, 5), movements.getFirst().day());
        assertEquals(new BigDecimal("201.00"), movements.getFirst().amount(), "montant + frais");
        assertEquals("id:a2", movements.getFirst().key(), "identifiant du courtier : pas de doublon au réimport");
        assertEquals(movements.getFirst().line(), BrokerExport.Movement.parse(movements.getFirst().line()).line(), "voyage intact de l'aperçu à l'import");
        assertTrue(BrokerExport.movements("Ticker;Type;Quantity\nAAPL;BUY;2").isEmpty(), "sans date, pas de mouvement");
    }

    @Test
    void titlesAreFoundAndHistoriesBuildThePortfolioCurve() {
        List<MarketFeeds.Found> found = MarketFeeds.parseSearch("""
                {"quotes":[{"symbol":"^990100-USD-STRD","quoteType":"INDEX","typeDisp":"Index"},{"exchange":"PAR","shortname":"AMUNDI MSCI WORLD","quoteType":"ETF","symbol":"CW8.PA","longname":"Amundi MSCI World UCITS ETF",\
                "exchDisp":"Paris","typeDisp":"ETF"},{"symbol":"BTC-EUR","shortname":"Bitcoin EUR","typeDisp":"Cryptocurrency"}],"news":[]}""");
        assertEquals("CW8.PA", found.getFirst().symbol());
        assertEquals("Amundi MSCI World UCITS ETF", found.getFirst().name());
        assertEquals("Paris", found.getFirst().exchange());
        assertEquals("BTC-EUR", found.get(1).symbol());
        MarketFeeds.History etf = MarketFeeds.parseHistory("""
                {"chart":{"result":[{"meta":{"currency":"EUR"},"timestamp":[1767225600,1767312000,1767398400],\
                "indicators":{"quote":[{"close":[100.0,null,110.0]}]}}]}}""");
        assertEquals(2, etf.dates().size(), "jour sans cours ignore");
        MarketFeeds.History dollar = MarketFeeds.parseHistory("""
                {"chart":{"result":[{"meta":{"currency":"USD"},"timestamp":[1767225600,1767398400],"indicators":{"quote":[{"close":[10,20]}]}}]}}""");
        MarketFeeds.History rate = MarketFeeds.parseHistory("""
                {"chart":{"result":[{"meta":{"currency":"EUR"},"timestamp":[1767225600],"indicators":{"quote":[{"close":[0.5]}]}}]}}""");
        List<SavingsRepository.Position> positions = List.of(
                new SavingsRepository.Position(1, 1, "CW8.PA", "ETF", new BigDecimal("2"), new BigDecimal("150"), null),
                new SavingsRepository.Position(2, 1, "AAPL", "Apple", new BigDecimal("1"), new BigDecimal("5"), null));
        StockCharts.Series curve = StockCharts.portfolio(positions, new BigDecimal("50"), MarketFeeds.Range.YEAR,
                symbol -> switch (symbol) { case "CW8.PA" -> etf; case "AAPL" -> dollar; default -> rate; });
        assertEquals(new BigDecimal("255.00"), curve.values().getFirst(), "50 de liquidites + 2 × 100 + 10 $ × 0,5");
        assertEquals(new BigDecimal("280.00"), curve.values().getLast(), "50 + 2 × 110 + 20 $ × 0,5 (dernier taux connu)");
    }

    @Test
    void foreignQuotesAreConvertedAndPenceBecomePounds() {
        assertEquals(new BigDecimal("87.53"), MarketFeeds.parseQuote("{\"currency\":\"GBp\",\"regularMarketPrice\":8753}").price());
        SavingsRepository.Position apple = new SavingsRepository.Position(1, 1, "AAPL", "Apple", new BigDecimal("2"), new BigDecimal("300"), null);
        Savings.PositionLine line = Savings.value(apple, Optional.of(new MarketFeeds.Quote(new BigDecimal("200"), "USD")), Optional.of(new BigDecimal("0.9")));
        assertEquals(new BigDecimal("360.00"), line.value());
        assertTrue(line.priceSource().contains("converti depuis USD"));
    }
}
