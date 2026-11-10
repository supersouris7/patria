// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Page publique « Licence » : resume en francais, puis les textes livres dans le jar (LICENSE, qui fait foi, et sa
 * traduction LICENSE.fr). Les memes mentions figurent en pied de page et au demarrage.
 */
public final class LicencePage {
    /** Mention ecrite au journal au demarrage. */
    public static final String NOTICE = "Patria © 2026 William Iv, sous licence Elastic 2.0 : installation libre et gratuite pour soi et ses proches,"
            + " fourniture à des tiers comme service hébergé réservée au concédant. Texte : /licence.";

    private LicencePage() {
    }

    public static Html render() {
        return Layout.document("Licence · Patria", "legal-page", Html.of("""
                <main class="shell legal">%s<h1>Licence</h1>
                <p><b>© 2026 William Iv.</b> Patria est distribué sous la licence <b>Elastic License 2.0</b>.</p>
                <ul><li>Vous pouvez l'installer gratuitement, pour vous, votre famille ou vos proches, le modifier et le redistribuer avec cette licence.</li>
                <li>Vous ne pouvez pas le proposer à des tiers comme service hébergé ou géré : ce service est réservé au titulaire des droits.</li>
                <li>Les mentions de droits et de licence ne doivent pas être retirées. Le nom « Patria » et son logo ne sont pas concédés.</li></ul>
                <details><summary>Traduction française (pour information)</summary><pre class="licence-text">%s</pre></details>
                <details><summary>Texte officiel (anglais, fait foi)</summary><pre class="licence-text">%s</pre></details>
                <p class="muted"><a href="/connexion">Retour</a></p></main>""", Layout.brand(), text("LICENSE.fr"), text("LICENSE")));
    }

    private static String text(String name) {
        try (InputStream in = LicencePage.class.getResourceAsStream("/licence/" + name)) {
            if (in == null) throw new IllegalStateException("Texte de licence absent du programme : " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
