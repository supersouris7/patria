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

## Caviardage (Stirling PDF)

`pdf/Stirling` (client REST) et `pdf/PdfController` (`/documents/preparer`). Patria garde toute l'interface ; le serveur Stirling PDF de l'installation sert de moteur, remplaçable sans toucher aux pages. Enchaînement : `POST /api/v1/misc/ocr-pdf` (si « Document scanné », `languages=fra,eng`, `ocrType=skip-text`), `POST /api/v1/security/auto-redact` (`listOfText` une expression par ligne, `useRegex=true`, `convertPDFToImage` selon la case ; nombre de zones lu dans `X-Stirling-Redaction-Matches`), puis `POST /api/v1/security/sanitize-pdf` (métadonnées, XMP, JavaScript, pièces jointes, liens). Clé API facultative en en-tête `X-API-KEY`. Le PDF ne transite qu'en mémoire (25 Mo au plus, limite propre à cette route dans `web/Request`).

Les appels avec clé API consomment l'**allocation de traitement** du serveur Stirling (réponse 402 une fois épuisée) : vérifier les quotas réels de la version installée. La liste exacte des opérations est sur l'instance : `/swagger-ui/index.html`, spécification `/v1/api-docs`. Le caviardage manuel par sélection graphique n'est pas intégré : il demanderait un éditeur dans Patria (PDF.js) et la vérification des coordonnées attendues par l'API.

Tests : `mvn verify` (un faux serveur Stirling vérifie l'enchaînement des appels et les champs envoyés).

## Publication Docker Hub

Un seul workflow : [`.github/workflows/docker.yml`](../.github/workflows/docker.yml), à modifier dans le dépôt (pas dans Portainer). Docker Hub est conservé ; aucun deuxième pipeline GHCR n'est nécessaire. Secrets GitHub requis : `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` (jeton d'accès Docker Hub en lecture et écriture).

| Déclencheur | Images publiées après tests Java et navigateur |
|---|---|
| Push sur `main` | `supersouris7/patria:unstable` |
| Tag Git stable `vX.Y.Z` | `supersouris7/patria:X.Y.Z`, puis promotion de `:latest` |

**Tags publiés** : `unstable` (chaque push sur `main`), `X.Y.Z` (chaque tag stable) et `latest`. Aucun ménage automatique : les anciennes versions restent sur Docker Hub. Ménage à la main (garde `latest`, `unstable` et la dernière `X.Y.Z`, efface les hashs de commit et les versions plus anciennes, ne touche à aucun autre nom) : `python3 scripts/docker_publish.py prune --dry-run` pour voir, puis `DOCKERHUB_USERNAME=… DOCKERHUB_TOKEN=… python3 scripts/docker_publish.py prune` avec un jeton qui a le droit Delete.

`latest` est promu séparément, sans reconstruire l'image : son manifeste amd64/arm64 est repris par digest. Les promotions sont sérialisées et choisissent la plus grande version numérique **déjà publiée sur Docker Hub**, pas le dernier workflow terminé. Un ancien tag relancé ne fait donc pas reculer `latest`. La version réelle de l'actuel `latest` est aussi vérifiée ; en cas de panne réseau, de version attendue absente ou de métadonnée inconnue, la promotion échoue ou refuse de rétrograder au lieu d'écraser l'alias à l'aveugle. Une promotion en attente remplacée par GitHub reste couverte par la recherche de la plus grande version publiée.

Pour publier une version, après avoir envoyé et vérifié les modifications sur `main` :

```bash
git switch main
git pull --ff-only
git tag -a v1.0.0 -m "Patria 1.0.0"
git push origin v1.0.0
```

Choisissez le numéro voulu ; cet exemple ne crée pas automatiquement la première version. La version affichée par l'application (pied de page, page Sécurité) est celle du tag pour une image stable : le workflow la passe à la construction (`APP_VERSION`, propriété Maven `app.version`). L'image `unstable` affiche la version de `pom.xml` ; montez-la avec chaque release. Les tags de préversion (`v1.0.0-rc.1`), formats incomplets et zéros initiaux ne sont pas publiés par ce workflow. Ne déplacez ni ne supprimez un tag de version déjà publié. Le lancement manuel du workflow accepte `main` ou un tag stable ; une relance sur un ancien tag republie cette version sans rétrograder `latest`.

**Publier un tag historique déjà existant** (par exemple `v0.5.0`) : après avoir envoyé ce workflow sur `main`, GitHub → Actions → Build and publish Docker image → Run workflow, choisissez la branche **main**, puis renseignez **stable_tag = v0.5.0**. Ou avec la CLI :

```bash
gh workflow run docker.yml --ref main -f stable_tag=v0.5.0
```

Le workflow utilise ses outils de publication actuels, mais teste et construit **le commit exact du tag demandé**, pas le dernier `main`. Il exécute les tests Java de cette version (le test navigateur de l'ancien outil de caviardage n'est plus lancé). Il ne remplace pas `unstable`. Les anciens tags peuvent afficher dans l'application une version Maven différente du numéro Docker ; ne déplacez pas leur tag pour corriger cela, alignez les versions lors des prochaines releases.

Le Compose garde `unstable` par défaut pour le serveur personnel. Les utilisateurs publics mettent `PATRIA_IMAGE=supersouris7/patria:latest` dans `.env`, ou fixent une version. La publication d'une image ne redéploie pas les conteneurs : garder votre mécanisme de mise à jour Portainer ou lancer `docker compose pull` puis `docker compose up -d` après sauvegarde.

Tests du routage et de la protection de `latest` (sans accès Docker Hub ni publication) :

```bash
python3 -m unittest discover -s tests -p 'test_docker_publish.py'
```

### Dépôt public

GitHub → Settings → General → Danger Zone → Change repository visibility ne sert que si le dépôt est encore privé. Vérifiez l'historique et les fichiers avant publication : `.gitignore` ne retire aucun ancien secret. Si un secret a déjà été envoyé, révoquez-le avant toute correction de l'historique. Un scan automatique sans alerte ne garantit pas l'absence de données personnelles. La visibilité des images Docker Hub se règle séparément de celle du dépôt GitHub.

Le pied de page affiche version et commit. La CI fournit `GIT_COMMIT` comme argument Docker ; en local, utilisez `-Dgit.commit=...` comme ci-dessus. `KOFI_URL=https://ko-fi.com/<nom>` configure le lien de soutien, une valeur vide le masque.
