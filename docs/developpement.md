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

## Aide IA et IA locale

Les prompts (`ExpenseCsv.llmPrompt`, `CsvScheduleParser.llmPrompt`, `savings/PositionCsv.llmPrompt`) se copient toujours ; la réponse se colle dans le champ `texte` des imports (`FormData.uploadedText`, balises de code retirées) ou s'envoie en fichier. `Components.llmPrompt` affiche l'avertissement sur les IA commerciales.

IA locale facultative (module `ai`) : `LocalAi` appelle le serveur Ollama réglé par l'administrateur (réglages `local_ai_*` de la base des comptes, page Sécurité) avec `POST /api/generate` (non diffusé, température 0) ; `PdfText` extrait le texte des PDF avec OpenPDF (pas d'OCR). Un formulaire d'import qui contient `Components.localAiInput(type)` envoie le champ `document` et `ia` (depenses, echeancier, bourse) ; `FormData.uploadedText` le fait convertir avant l'aperçu habituel (convertisseur posé par `App`). Envois de fichiers : 10 Mo au plus.

## Publication Docker Hub

Un seul workflow : [`.github/workflows/docker.yml`](../.github/workflows/docker.yml) (tests Java, contrôle amd64/arm64 et Trivy, puis publication), à modifier dans le dépôt (pas dans Portainer). Docker Hub est conservé ; aucun deuxième pipeline GHCR n'est nécessaire. Secrets GitHub requis : `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` (jeton d'accès Docker Hub en lecture et écriture).

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

Le workflow utilise ses outils de publication actuels, mais teste et construit **le commit exact du tag demandé**, pas le dernier `main`. Il exécute les tests Java de cette version. Il ne remplace pas `unstable`. Les anciens tags peuvent afficher dans l'application une version Maven différente du numéro Docker ; ne déplacez pas leur tag pour corriger cela, alignez les versions lors des prochaines releases.

Le Compose garde `unstable` par défaut pour le serveur personnel. Les utilisateurs publics mettent `PATRIA_IMAGE=supersouris7/patria:latest` dans `.env`, ou fixent une version. La publication d'une image ne redéploie pas les conteneurs : garder votre mécanisme de mise à jour Portainer ou lancer `docker compose pull` puis `docker compose up -d` après sauvegarde.

Tests du routage et de la protection de `latest` (sans accès Docker Hub ni publication) :

```bash
python3 -m unittest discover -s tests -p 'test_docker_publish.py'
```

### Dépôt public

GitHub → Settings → General → Danger Zone → Change repository visibility ne sert que si le dépôt est encore privé. Vérifiez l'historique et les fichiers avant publication : `.gitignore` ne retire aucun ancien secret. Si un secret a déjà été envoyé, révoquez-le avant toute correction de l'historique. Un scan automatique sans alerte ne garantit pas l'absence de données personnelles. La visibilité des images Docker Hub se règle séparément de celle du dépôt GitHub.

Le pied de page affiche version et commit. La CI fournit `GIT_COMMIT` comme argument Docker ; en local, utilisez `-Dgit.commit=...` comme ci-dessus. `KOFI_URL=https://ko-fi.com/<nom>` configure le lien de soutien, une valeur vide le masque.

## Vulnérabilités (CVE) et contrôle des images

- **Image** : base Wolfi `cgr.dev/chainguard/glibc-dynamic` figée par son empreinte (7 paquets, sans shell) ; bibliothèque native de SQLite extraite dans `/app/native` pour un `/tmp` non exécutable. Détail et mesures : [audit de sécurité](audit-securite.md).
- **CI** (`.github/workflows/docker.yml`, job `verify`, avant toute publication, pour amd64 et arm64) : construction, `scripts/smoke_image.py` (démarrage durci, chiffrement, en-têtes, utilisateur non root), puis Trivy (image officielle figée par empreinte). Rapport complet (JSON et tableau) gardé 90 jours en artefact et affiché dans le résumé du job. **Bloquant** : vulnérabilité critique ou élevée avec un correctif disponible. Les vulnérabilités sans correctif restent visibles dans le rapport sans bloquer. Exception : `.trivyignore`, avec justification et preuve.
- **Ancienne version republiée** (`stable_tag`) : les outils de contrôle viennent de la révision du workflow ; une image antérieure à ces contrôles est testée en mode `--legacy` (sans lecture seule ni nouveaux en-têtes).
- **Localement** : `docker build -t patria:test . && python3 scripts/smoke_image.py patria:test`, puis `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.76.2 image patria:test`.
- **Mises à jour** : Dependabot (`.github/dependabot.yml`) propose chaque semaine l'empreinte de l'image de base, les actions GitHub et les dépendances Maven, en pull request.
- **Corriger** : le plus souvent une nouvelle construction suffit (base Wolfi et Java 21 repris à leur dernière version) ; sinon monter la dépendance dans `pom.xml`.
- **En-têtes HTTP** posés par `web/Router` : CSP stricte (aucun script en ligne, `frame-ancestors 'none'`), `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Resource-Policy`, HSTS en HTTPS ; cookies `HttpOnly`, `SameSite=Lax`, `Secure` en HTTPS. Formulaire public de demande de compte : 3 demandes par adresse et par heure, 30 en attente au plus.
