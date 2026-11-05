# Développement et publication

[Présentation](../README.md) · [Architecture](../ARCHITECTURE.md) · [Administration](administration.md)

## Lancer en local

Prérequis : Java 21 et Maven 3.9+.

```bash
mvn verify
mvn package -Dgit.commit=$(git rev-parse HEAD)
APP_PASSWORD=mot-de-passe-local DATABASE_PATH=./patria.sqlite3 java -Xms16m -Xmx96m -jar target/patria.jar
```

Le mot de passe doit être défini **au lancement de Java**, pas seulement pendant la construction. Le port Java est `HTTP_PORT` (8080 par défaut) ; `PORT` ne concerne que Docker Compose. Pour des données réelles, configurez aussi `DB_ENCRYPTION_KEY`. Ne versionnez ni secrets ni fichiers de données.

Serveur HTTP natif du JDK, SQLite, montants au centime, HTML généré côté serveur, modules sans Spring/Tomcat/pool de connexions. Voir [l'architecture](../ARCHITECTURE.md) pour les dépendances, migrations et tests.

## Maintenance des coefficients

Les coefficients légaux de revalorisation foncière sont dans `src/main/java/fr/patria/expense/PropertyTaxRevaluation.java`. Ajouter chaque année le coefficient publié ; sinon le dernier connu est repris. Le comportement estimatif doit rester explicite pour les utilisateurs.

## Caviardage

`ui/RedactionPage`, `static/js/redaction.mjs` (sélection/rastérisation) et `redaction-pdf.mjs` (PDF neuf d'images seulement). Pas de route de téléversement, de stockage de documents ni de service IA.

PDF.js est livré localement avec ses licences et ressources : [version, intégrité et maintenance](../src/main/resources/static/vendor/pdfjs/README.md). Il s'agit d'un parseur de documents non fiables : suivre ses mises à jour de sécurité. La CSP n'autorise l'exécution WASM que sur la page de caviardage, sans `unsafe-eval` JavaScript ni service externe.

Tests Java : `mvn verify`. Test navigateur supplémentaire (Chromium installé) :

```bash
uv venv /tmp/opencode/patria-browser-tests
uv pip install --python /tmp/opencode/patria-browser-tests/bin/python playwright pymupdf pillow
/tmp/opencode/patria-browser-tests/bin/python tests/redaction_browser.py http://127.0.0.1:8080
```

Utiliser une installation locale de test sans données réelles. Le test génère ses documents synthétiques et vérifie pixels, absence de texte/métadonnées/pièces jointes et absence d'envoi réseau des fichiers.

## Publication Docker Hub

Chaque push sur `main` lance les tests puis construit et publie `supersouris7/patria:unstable` pour amd64/arm64. Secrets GitHub requis : `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` (accès lecture/écriture).

Le pied de page affiche version et commit. La CI fournit `GIT_COMMIT` comme argument Docker ; en local, utilisez `-Dgit.commit=...` comme ci-dessus. `KOFI_URL=https://ko-fi.com/<nom>` configure le lien de soutien, une valeur vide le masque.
