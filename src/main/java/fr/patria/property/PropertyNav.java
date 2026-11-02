package fr.patria.property;

import fr.patria.ui.Components;
import fr.patria.ui.Components.NavItem;
import fr.patria.ui.Html;
import java.util.List;

/** Navigation laterale des pages d'administration d'un bien (aussi utilisee par le module patrimoine). */
public final class PropertyNav {
    public enum Tab { INFORMATION, FINANCING, EXPENSES, RENTS }

    private PropertyNav() {
    }

    public static Html of(Property property, Tab active) {
        return Components.sideNav(property.name(), List.of(
                new NavItem("/administration/bien/" + property.id(), "Informations", active == Tab.INFORMATION),
                new NavItem("/administration/bien/" + property.id() + "/financements", "Prêts et apports", active == Tab.FINANCING),
                new NavItem("/administration/bien/" + property.id() + "/charges", "Charges", active == Tab.EXPENSES),
                new NavItem("/administration/bien/" + property.id() + "/loyers", "Loyers", active == Tab.RENTS),
                new NavItem("/bien/" + property.id(), "Consulter le bien →", false)));
    }
}
