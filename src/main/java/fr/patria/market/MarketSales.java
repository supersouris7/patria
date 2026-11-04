package fr.patria.market;

import java.util.List;

/**
 * Detail des ventes ayant servi au dernier prix du marche d'un bien, avec la position du bien (centre de la carte DVF),
 * {@code null} si elle n'est pas connue.
 */
public record MarketSales(MarketPrice price, Double latitude, Double longitude, List<MarketSale> sales) {
}
