package fr.patria.ui;

/** Caviardage local : seuls les fichiers statiques sont demandes au serveur. */
public final class RedactionPage {
    private RedactionPage() {
    }

    public static Html render(String csrf) {
        return Layout.page("Caviarder avant l'import · Patria", Layout.Section.NONE, csrf, Html.concat(
                Components.heading("Avant l'import", "Caviarder vos documents", "Supprimez les informations personnelles avant de partager une copie avec une IA.", Html.EMPTY),
                Html.of("<script type=\"module\" src=\"%s\"></script>", Assets.url("/js/redaction.mjs")),
                Html.raw("""
                        <section class="card section" id="redaction">
                        <p><b>Tout se passe dans votre navigateur.</b> Aucun document n'est envoyé au serveur Patria ni à un service externe.
                        PDF.js est fourni avec Patria, sans CDN. Rien n'est conservé par l'outil après fermeture de la page.</p>
                        <p>1. Ouvrez un PDF, ou une image PNG/JPEG. 2. Sur <b>chaque page</b>, encadrez les noms, adresses, IBAN, numéros et autres informations
                        identifiantes (souris ou doigt), ou saisissez une zone en pourcentage au clavier. Prévoyez une marge autour du texte.
                        3. Vérifiez chaque page et téléchargez la copie. <b>Joignez uniquement cette copie à votre IA, jamais l'original.</b></p>
                        <p class="notice warning">La sélection est manuelle : aucune détection automatique n'est garantie. Les montants, dates et libellés laissés
                        visibles peuvent encore vous identifier. Un prompt d'anonymisation ne protège pas un document déjà envoyé.</p>
                        <p class="muted">Le PDF exporté ne contient que les images caviardées, sans texte sélectionnable, pièces jointes, liens, formulaires ni
                        métadonnées d'origine. La recherche et l'accessibilité du PDF sont perdues ; une IA devra lire les images. L'original reste intact.
                        Limites : 15 Mio, 30 pages, 12 millions de pixels par page ; les PDF protégés par mot de passe ne sont pas acceptés.</p>
                        <noscript><p class="notice error">JavaScript est nécessaire. N'envoyez pas votre original : utilisez un outil de caviardage local.</p></noscript>
                        <label>Document à caviarder<input id="redaction-file" type="file" accept="application/pdf,image/png,image/jpeg,.pdf,.png,.jpg,.jpeg"></label>
                        <p id="redaction-status" role="status" aria-live="polite">Chargement de l'outil…</p>
                        <fieldset id="redaction-editor" hidden>
                        <legend>Zones à supprimer</legend>
                        <div class="form-actions"><button type="button" id="redaction-previous">Page précédente</button>
                        <span id="redaction-page"></span><button type="button" id="redaction-next">Page suivante</button></div>
                        <div class="redaction-viewport"><canvas id="redaction-canvas" aria-label="Page du document. Utilisez les champs ci-dessous pour ajouter une zone au clavier."></canvas></div>
                        <div class="form-grid" id="redaction-coordinates">
                        <label>Gauche (%)<input id="redaction-x" type="number" min="0" max="99" step="0.1" value="0"></label>
                        <label>Haut (%)<input id="redaction-y" type="number" min="0" max="99" step="0.1" value="0"></label>
                        <label>Largeur (%)<input id="redaction-width" type="number" min="0.1" max="100" step="0.1" value="30"></label>
                        <label>Hauteur (%)<input id="redaction-height" type="number" min="0.1" max="100" step="0.1" value="10"></label></div>
                        <div class="form-actions"><button type="button" id="redaction-add">Supprimer cette zone</button>
                        <button type="button" id="redaction-undo">Annuler la dernière zone</button>
                        <button type="button" id="redaction-all">Supprimer toute la page</button></div>
                        <label><input type="checkbox" id="redaction-reviewed"> J'ai vérifié cette page et ses zones supprimées.</label>
                        <p id="redaction-progress"></p>
                        <button type="button" id="redaction-export" disabled>Télécharger le PDF caviardé</button>
                        </fieldset>
                        <button type="button" id="redaction-clear">Fermer le document et effacer le travail en mémoire</button>
                        <p><a href="/confidentialite">Confidentialité et limites</a> · Fermez cet onglet pour revenir à l'import.</p>
                        </section>""")));
    }
}
