# Architecture de Patria

Patria est une application web Java 21 **sans framework** : serveur HTTP du JDK, SQLite, HTML généré côté serveur.
Le code est découpé en **modules** (packages Java) qui ne dépendent les uns des autres que dans un seul sens.

## Carte des modules

```
fr.patria
│
├─ App, Config ............ assemble tous les modules et démarre le serveur (seul endroit où l'on fait « new »)
├─ HouseholdSites .......... bases des foyers (une par foyer), ouvertes, créées, mises de côté
├─ LoginAccounts ........... ce que la connexion demande aux comptes (mot de passe, 2FA, journal)
├─ PropertyMove ............ déplacement d'un bien entre deux bases de foyer
├─ HouseholdSite ........... un foyer : sa base SQLite, ses dépôts, ses contrôleurs et son routeur
│
├─ account ................. COMPTES : foyers, utilisateurs, droits (base comptes.sqlite3), Mon compte
├─ wizard .................. GUIDE DE SAISIE : import guidé par étapes, revue annuelle
├─ health .................. BILAN DE SANTÉ : diagnostic des finances, des biens et des impôts (lecture seule)
│
├─ budget .................. BUDGET : accueil, prévisionnel des 12 mois (reprend l'immobilier), dépenses du foyer
├─ household ............... FOYER : personnes, revenus, répartition entre les personnes (Shares)
├─ portfolio ............... PATRIMOINE : vues immobilières (immobilier, fiche d'un bien, mensualités, biens et prêts)
│   ├─ loan ................ PRÊTS : import CSV des échéanciers, calculs à date, pages d'un prêt
│   ├─ contribution ........ APPORTS : apports personnels d'un bien
│   ├─ market .............. MARCHÉ : prix au m² du secteur (ventes DVF de data.gouv.fr), calculé en arrière-plan
│   ├─ expense ............. CHARGES : taxe foncière, copropriété, assurance par année, années manquantes estimées
│   ├─ rent ................ OCCUPATION : périodes de location ou de résidence principale (loyer économisé)
│   └─ property ............ BIENS : modèle, scénarios de valeur, formulaire d'un bien
│
├─ auth .................... connexion, déconnexion, limitation des tentatives
├─ web ..................... routeur, requête/réponse, formulaires, sessions, CSRF
├─ storage ................. connexion SQLite, conversions, migrations versionnées
├─ ui ...................... Html échappé par défaut, mise en page, composants, formats français
└─ common .................. exceptions métier (ValidationException, NotFoundException)
```

### Règles de dépendance

| Module | Peut utiliser |
|---|---|
| `common` | rien |
| `ui`, `storage` | `common` |
| `web` | `common`, `ui` |
| `auth` | `common`, `ui`, `web` |
| `property` | `common`, `ui`, `web`, `storage` |
| `loan`, `contribution`, `market`, `expense`, `rent` | idem + `property` |
| `household`, `account` | `common`, `ui`, `web`, `storage` |
| `portfolio` | modules immobiliers ci-dessus |
| `budget` | modules immobiliers + `household` |
| `wizard` | modules immobiliers + `household` + `budget` |
| `health` | modules immobiliers + `household` + `budget` + `portfolio` |

Ces règles sont **vérifiées par un test** (`ArchitectureTest`). Un import interdit fait échouer le build.
Pour ajouter un module ou une dépendance, il faut le déclarer dans ce test : c'est un choix explicite.

## Anatomie d'un module métier

Chaque module métier suit la même organisation, ce qui permet de s'y retrouver d'un module à l'autre :

| Fichier | Rôle | Exemple (`loan`) |
|---|---|---|
| `Xxx.java` | données (record immuable) | `Loan`, `ScheduleLine`, `LineType` |
| `XxxRepository` | lecture/écriture SQLite, rien d'autre | `LoanRepository` |
| calculs | fonctions pures, testées sans base | `LoanMath`, `CsvScheduleParser` |
| `XxxController` | déclare les routes, lit le formulaire, appelle le dépôt, choisit la page | `LoanController` |
| `XxxPages` | HTML uniquement, aucun calcul | `LoanPages` |
| `package-info.java` | description du module | |

## Parcours d'une requête

```
Navigateur ──► Router principal (web)
                 ├─ fichier statique ? → servi avec cache d'un an (versionné par empreinte)
                 ├─ session → Viewer (droits relus dans comptes.sqlite3 ; compte désactivé → déconnecté)
                 ├─ routes de connexion, foyers, utilisateurs, Mon compte
                 └─ sinon → Router du foyer affiché (HouseholdSite, une base par foyer)
                              ├─ route privée sans session → redirection /connexion?suite=<page demandée>
                              ├─ route réservée (Access) → 403
                              ├─ POST privé sans jeton CSRF valide → 400
                              └─ Controller.handle(Request) ──► Repository / calculs ──► Pages ──► Response

ValidationException → 400 · ForbiddenException → 403 · NotFoundException → 404 · autre → 500 journalisée
```

Le routeur applique ces règles **une seule fois** pour toutes les routes : un contrôleur n'a jamais à vérifier
la session ou le jeton CSRF, ni à construire une page d'erreur.

### Droits

- **Isolation des foyers** : chaque foyer a sa base et son jeu complet de dépôts et de contrôleurs (`HouseholdSite`).
  Une requête ne peut donc lire que le foyer de la session ; aucune requête SQL n'a de filtre « foyer ».
- **Niveau de la route** (`Router.Access`) : `get`/`post` sont réservées **par défaut** : sur le routeur
  principal (foyers, utilisateurs, déplacement d'un bien) à l'administrateur de l'application
  (`Viewer.isAdministrator()`), sur le routeur d'un foyer à qui le gère (`Viewer.manages()` : l'administrateur, ou
  un utilisateur **gestionnaire** de ce foyer) ; `userGet`/`userPost` ouvrent une page aux utilisateurs ;
  `estateGet` à ceux qui consultent l'immobilier.
- **L'administrateur ne voit aucune donnée des foyers** : `Viewer.admin` n'a ni vue ni gestion ; le routeur
  principal ne lui transmet aucune page d'un foyer (`/` et `/administration` le renvoient à Foyers, le reste en 403).
  Il gère seul les comptes et les droits (routes `get`/`post` du routeur principal). Les données se saisissent avec
  un compte **gestionnaire** (`Access.manager`).
- **Transparence** : comme il crée les comptes, l'administrateur pourrait s'en ouvrir un. Chaque changement de droits
  est donc inscrit dans `audit_log` (page Sécurité) et montré aux comptes du foyer : icône « qui voit quoi » de
  l'en-tête (`account/AudienceText`, calculée par `account/SessionViewers`), avec les changements depuis la dernière lecture.
  Un mot de passe choisi par l'administrateur (création, réinitialisation) est marqué `must_change_password` : le
  routeur n'ouvre aucune autre page avant qu'il soit changé (`/compte/nouveau-mot-de-passe`), et la page dit qui
  l'a choisi et quand.
- **Lignes** : sur une route ouverte, le contrôleur filtre avec `household/Visibility` (à partir de
  `Request.viewer()`) : ligne visible si un de ses membres a sa vue ouverte, modifiable si tous l'ont.
- **En-tête** : `Layout.CHROME` (posé par le routeur le temps de la requête) donne le nom, les foyers et les
  rubriques permises ; `Layout.manages()` masque les actions réservées dans les pages partagées,
  `Layout.canOpen(href)` les liens qui mèneraient à « Accès réservé ». Il porte aussi l'alerte HTTP
  (`insecure`, fenêtre de `ui/SecurityPages`).

## Où se trouve quoi

| Question | Réponse |
|---|---|
| Comment est calculé le capital restant dû ? | `loan/LoanMath.status` |
| D'où viennent les scénarios pessimiste / prudent / fourchette / optimiste ? | `property/Valuation` |
| Comment sont additionnés les biens sur la vue d'ensemble ? | `portfolio/Portfolio.overview` |
| Quel format CSV est accepté ? | `loan/CsvScheduleParser` (aide affichée par `LoanPages.csvHelp`) |
| Comment est géré le curseur côté navigateur ? | `static/js/app.js`, enregistré par `PropertyController.saveSelectedValue` |
| Où sont les textes des messages de confirmation ? | `ui/Notice` |
| Où est le schéma de la base ? | `storage/Migrations.createSchema` |
| Comment est calculé le prix du marché ? | `market/MarketAnalysis` (filtres, zone, médiane), `market/PriceIndex` (actualisation) |
| Quand le prix du marché est-il recalculé ? | `market/MarketService` (cache 30 jours, calcul en arrière-plan) |
| Où sont les ventes affichées dans « Voir les N ventes en détail » ? | retenues par `market/MarketAnalysis.analyse`, enregistrées avec le prix par `MarketRepository.save` : table `market_sales` et position du bien (`market_prices.latitude/longitude`, migration v12) ; page `market/MarketPages.sales` |
| D'où vient le prompt IA des imports ? | `budget/ExpenseCsv.llmPrompt`, `loan/CsvScheduleParser.llmPrompt` (construits depuis les catégories, fréquences et types), affichés par `ui/Components.llmPrompt`, copiés par `app.js` |
| Comment caviarder les documents avant une IA ? | `/aide/caviardage`, `ui/RedactionPage`, `static/js/redaction.mjs`, moteur PDF.js embarqué ; PDF reconstruit d'images caviardées par `redaction-pdf.mjs`. Aucun téléversement ni stockage. |
| Quelles API externes sont appelées ? | `market/DataGouvClient` (data.gouv.fr) et `market/HousingRates` (BCE), sans clé |
| Comment est construit le prévisionnel du foyer ? | `budget/Forecast` (mois par mois, endettement, alertes, épargne de précaution) |
| Comment est calculée la part d'une personne ? | `household/Shares.weight` (vide = parts égales), appliquée par `Forecast.Scope` ; table `shares` (migration v8) |
| Quel format CSV pour importer des dépenses ? | `budget/ExpenseCsv` (lecture, modèle), enregistrement en une transaction par `BudgetRepository.insertAll` |
| Comment comptent les virements et le compte joint ? | `household/Transfer`, lignes ajoutées par `Forecast.month` (vue d'un membre seulement) ; crédits du compte joint dans `Forecast.jointCredits` ; membre de type compte : `Person.Kind` (migration v9) |
| Quelles valeurs une saisie accepte-t-elle ? | `common/InputRules` (nombres simples sans notation scientifique, montants ≤ 1 milliard, dates 1900–2100, textes sans caractères de contrôle), appliqué par `web/FormData` et les imports CSV (`budget/ExpenseCsv`, `loan/CsvScheduleParser`) |
| Où mènent les retours (après connexion, après enregistrement depuis le budget) ? | `web/LocalPath.safe` : adresse interne uniquement, sinon page par défaut |
| Quels mois pour la taxe foncière, la copropriété… dans le budget ? | `budget/Forecast.propertyTax` et `otherCharges` (appelées par `Forecast.month`) ; taxe foncière mensualisée (10 prélèvements, janvier à octobre) : table `property_tax_monthly` (migration v10), choisie dans les charges du bien ; payeur de la taxe foncière (compte joint, personne) : table `property_tax_payers` (migration v11), choisi dans Administration → Répartition, appliqué par `Forecast.month` |
| Qui voit quoi (foyers, vues, immobilier) ? | `account/AccountRepository` (tables `households`, `users`, `user_households`, `user_members`), relu à chaque requête par `account/SessionViewers` (état du compte en une requête : `AccountRepository.state`) ; appliqué par `web/Router` (niveau de route) et `household/Visibility` (lignes) |
| Comment un bien change-t-il de foyer ? | `PropertyMove` (copie par ATTACH dans la base d'arrivée, nouveaux identifiants, puis suppression au départ), route `/administration/bien/{id}/foyer` déclarée par `PropertyMove.register` |
| Comment sont découpées les pages de comptes ? | `account/HouseholdsController` (Foyers, gestionnaire, reprise), `UsersController` (comptes, droits), `MyAccountController` (Mon compte, mots de passe, 2FA), socle commun `AccountSupport` ; dépôts `AccountRepository` (foyers, comptes, droits), `SecurityLog` (journaux), `TwoFactorStore` (2FA) |
| Où sont les styles ? | `static/css/parts/*.css`, réunis dans l'ordre de `parts/files.txt` en une seule feuille `/css/patria.css` par `ui/Assets.stylesheet` (l'ordre compte : cascade) |
| Où sont les pages du guide de saisie ? | `wizard/WizardPages` (accueil, étapes, documents, récapitulatif), `WizardBudgetPages` (budget), `WizardEstatePages` (immobilier), `AnnualReviewPages` (revue annuelle, rappels) |
| Où est la base d'un foyer ? | `HouseholdSites` : foyer principal = DATABASE_PATH, les autres `foyer-N.sqlite3` à côté ; ouverte et migrée à la première visite (toutes au démarrage avec DB_ENCRYPTION_KEY) |
| Qui gère un foyer ? | `account/Access.manager` (case « Gestionnaire du foyer »), lu par `account/SessionViewers` (`Viewer.Rights.MANAGER`) ; reprise des foyers sans gestionnaire : `HouseholdsController.takeOver` |
| Où sont les connexions et le journal des droits ? | tables `login_events` (90 jours) et `audit_log` de `comptes.sqlite3`, écrites par `LoginAccounts.recorded` (connexion) et `AccountSupport.audit` (droits) dans `account/SecurityLog` ; page `account/SecurityController` (`/administration/securite`) ; pays et ville : `account/IpLocator` (cache `ip_locations`) |
| Comment marche la double authentification ? | codes TOTP : `common/Totp` (RFC 6238, un code ne sert qu'une fois : `two_factor.last_step`) ; QR code : `ui/QrCode` (bibliothèque qrcodegen) ; deuxième étape de la connexion : `AuthController` (`/connexion/code`, appareils retenus 30 jours : `trusted_devices`, empreinte SHA-256) ; activation dans `MyAccountController` (`/compte/double-authentification`), dépôt `account/TwoFactorStore`, table `two_factor` (migration v3 de `comptes.sqlite3`) ; « fortement recommandé » : `account/Exposure` |
| D'où vient la dernière connexion de l'en-tête ? | `account/LoginHistory.recent` (connexion réussie précédant la session, échecs depuis), calculée par `account/SessionViewers`, affichée par `Layout.lastLogin` |
| D'où vient « qui voit quoi » ? | `account/AudienceText` (phrases), changements : `SecurityLog.accessChanges` depuis `users.audience_seen_at` ; icône : `Layout.audience` |
| Comment sont chiffrées les bases ? | `storage/Encryption`, `storage/Database.open(path, key)` ; clé lue par `Config` (DB_ENCRYPTION_KEY ou DB_ENCRYPTION_KEY_FILE) |
| D'où vient l'alerte HTTP ? | `web/Router.insecure` (ni X-Forwarded-Proto https, ni localhost), lue une fois par session (`Session.httpAcknowledged`) ; fenêtre et aide `/aide/https` : `ui/SecurityPages` |
| Où est l'accueil de la première connexion ? | `account/WelcomeController`, `account/WelcomePages` ; vu ou non : `users.welcomed_on`, `settings` pour l'administrateur |
| D'où viennent la version et le commit du pied de page ? | `ui/BuildInfo`, fichier filtré `src/main/build-info/patria-build.properties` (`-Dgit.commit=...` et `-Dapp.version=...`, arguments Docker `GIT_COMMIT` et `APP_VERSION` : numéro du tag stable) ; lien Ko-fi : `Config.supportUrl` (KOFI_URL) |
| D'où vient le taux du marché affiché à côté des prêts ? | `market/HousingRates` (BCE, série MIR France, taux fixe plus de 10 ans) ; comparaison et repère de renégociation calculés par `loan/Renegotiation` (fonctions pures) |
| Où sont les étapes et les listes de l'assistant ? | `wizard/Step` (ordre, étapes réservées), `wizard/Documents` (documents et coches automatiques), `wizard/Checklist` (dépenses par thème) ; progression dans `wizard/WizardRepository` (tables `wizard_steps`, `wizard_documents`, migration v13) |
| Où sont les règles du bilan de santé ? | `health/HealthChecks` (fonctions pures, seuils dans les explications) ; vue de la personne par `budget/BudgetScope` |
| Comment le Prévisionnel affiche-t-il les rappels ? | `BudgetController.withHomeExtras`, branché dans `HouseholdSite` : progression du guide (`WizardController.progress`), revue annuelle (`WizardController.reminder`) et bilan (`HealthController.summary`) |
| Où sont filtrées et triées les ventes du secteur ? | `market/SalesAnalysis` (filtres, « les plus comparables », médiane), graphiques `market/SalesChart`, carte de la fiche `market/SalesCard` |
| Comment sont produites les synthèses PDF ? | `ui/PdfReport` (mise en page A4 commune, OpenPDF, polices standard) ; contenu par `budget/BudgetReports`, `portfolio/PortfolioReports`, `loan/LoanReports` ; routes `*.pdf` dans les contrôleurs de chaque page |
| Comment est dessiné le graphique ? | `budget/BudgetChart` (SVG côté serveur, palette validée dans `static/css/parts/04-budget.css`) |
| Comment est calculé l'enrichissement réel ? | `portfolio/Portfolio.returns` |
| Comment l'estimation est-elle actualisée ? | coefficient calculé par `market/MarketService.compute` (indice DVF depuis la date d'estimation), appliqué par `property/Valuation.scenarios(property, facteur)` |
| Comment sont estimées les charges non saisies ? | `expense/ExpenseMath`, coefficients dans `expense/PropertyTaxRevaluation` |
| Comment sont comptés les loyers perçus et économisés ? | `rent/RentMath` (au jour près, jours sans période = vacants) |

## Base de données

- **Aucun nombre flottant** : montants en centimes (`INTEGER`), taux et surfaces en décimal exact (`TEXT`),
  dates ISO (`TEXT`). Les conversions sont centralisées dans `storage/Sql`.
- Une valeur absente vaut `NULL` (jamais 0 utilisé comme « pas de valeur »).
- Supprimer un bien supprime ses prêts, échéanciers et apports (`ON DELETE CASCADE`).
- Une seule connexion partagée, protégée par un verrou : SQLite n'accepte qu'un écrivain à la fois.

### Faire évoluer le schéma

1. Ajouter une méthode `toVersionN` dans `storage/Migrations`, l'appeler dans `apply`, incrémenter `LATEST`.
2. Mettre à jour `createSchema` pour qu'une base neuve soit directement au dernier schéma.
3. Ajouter un cas dans `MigrationsTest`.

Ne jamais modifier une migration déjà publiée. Avant toute migration d'une base existante, une copie complète
est faite à côté du fichier : `immobilier.sqlite3.avant-migration-vN-AAAAMMJJ-HHMMSS`.

La base des comptes (`comptes.sqlite3`) a ses propres versions, dans `AccountRepository.migrate` (v2 : foyer
gestionnaires, accueil de la première connexion, mot de passe provisoire, journaux des connexions et des droits,
localisation des adresses IP).

### Chiffrement

`storage/Encryption` (pilote `io.github.willena:sqlite-jdbc`, SQLite3 Multiple Ciphers, ChaCha20) : avec
DB_ENCRYPTION_KEY, `Database.open(path, key)` chiffre sur place une base encore en clair, puis l'ouvre avec la
clé ; au démarrage, `App` chiffre les copies « avant-migration » et les foyers supprimés, et ouvre toutes les bases.
`VACUUM INTO` (copies avant migration) écrit une copie chiffrée avec la même clé ; `Database.attachStatement`
ajoute la clé à un `ATTACH` (déplacement d'un bien). Une base chiffrée ouverte sans la bonne clé est refusée au
démarrage avec un message explicite.

## Interface

- `ui/Html` : tout texte inséré dans un gabarit est **échappé automatiquement**. Seul un objet `Html` est
  inséré tel quel. On écrit donc `Html.of("<td>%s</td>", bien.name())` sans jamais penser à l'échappement.
- `ui/Components` : tuiles, cartes, tableaux, champs de formulaire, zone de suppression…
- `ui/Format` : euros, pourcentages, dates, surfaces et durées à la française.
- Les pages ne font **aucun calcul** : elles reçoivent des données déjà calculées (`Portfolio`, `LoanMath`).

## Ajouter une fonctionnalité : exemple

Ajouter une page « Charges » à un bien :

1. Créer le package `fr.patria.charge` avec `Charge` (record), `ChargeRepository`, `ChargeController`, `ChargePages`.
2. Créer la table dans une nouvelle migration (`Migrations.toVersion3`).
3. Déclarer les routes dans `ChargeController.register` (`router.get("/bien/{id}/charges", ...)`).
4. Brancher le contrôleur dans `App.start`.
5. Déclarer le module dans `ArchitectureTest` (il peut utiliser `property`) et, si la vue d'ensemble doit
   l'afficher, l'ajouter aux dépendances de `portfolio`.

## Appels réseau

Le module `market` appelle l'extérieur : `MarketData` (implémentée par `DataGouvClient`, data.gouv.fr) et
`HousingRates` (taux moyen des crédits immobiliers, API publique de la BCE, gardé en mémoire et rafraîchi chaque jour,
partagé par tous les foyers).
Ces appels ne bloquent jamais une page : le calcul est lancé en arrière-plan par `MarketService`, la fiche du bien
affiche l'ancien résultat ou « calcul en cours » et se recharge quand il est terminé (`app.js`). La vue d'ensemble
lit seulement le résultat en base. Une panne de data.gouv.fr n'efface pas un résultat valable ; l'indice
départemental (API lente) est facultatif : sans lui, le prix est affiché « non actualisé » et recalculé le lendemain.

Hors du module `market`, `account/IpLocator` demande à ipwho.is (HTTPS, sans clé) le pays et la ville des adresses
IP du journal des connexions : seule l'adresse est envoyée, une fois, en arrière-plan ; le résultat est gardé dans
`ip_locations`. Les adresses locales ne sont jamais envoyées.

`OFFLINE=true` coupe tous ces appels (`MarketService` et `HousingRates` en mode hors ligne). Les messages d'erreur
n'incluent jamais les paramètres d'URL (l'adresse d'un bien). La page publique `/confidentialite` (`ui/PrivacyPage`)
décrit ces flux pour les utilisateurs.

L'image Docker est construite avec `jlink` : tout module JDK nécessaire doit figurer dans le `Dockerfile`
(`java.net.http` et `jdk.crypto.ec` pour HTTPS, `jdk.localedata` pour les formats français, `java.desktop` et
`java.xml` pour OpenPDF, lancé en mode `java.awt.headless`).

## Tests

| Niveau | Fichiers | Ce qui est vérifié |
|---|---|---|
| Calculs | `LoanMathTest`, `ValuationTest`, `CsvScheduleParserTest` | règles métier, sans base ni HTTP |
| Stockage | `PropertyRepositoryTest`, `MigrationsTest` | montants exacts, cascade, migration d'une ancienne base |
| Service | `PortfolioTest` | sommes de la vue d'ensemble |
| Marché | `MarketAnalysisTest`, `DvfSalesTest`, `MarketServiceTest`, `JsonTest` | filtres, zone, actualisation, cache, pannes réseau (source simulée, sans réseau) |
| Charges et loyers | `ExpenseMathTest`, `RentMathTest` | estimation, revalorisation, prorata, vacance |
| HTML | `HtmlTest` | échappement |
| Application | `AppTest` | vrai serveur HTTP : connexion, CSRF, parcours complet, 404 |
| Assistant | `WizardTest` | vrai serveur HTTP : étapes, coches, listes à cocher, progression, droits d'un utilisateur |
| Comptes | `AccountsTest` | vrai serveur HTTP : vues ouvertes, lecture seule, pages réservées, mot de passe, foyers séparés |
| Architecture | `ArchitectureTest` | sens des dépendances entre modules |

Les tests utilisent une horloge fixe (`TestData.CLOCK`) : les calculs « à date » sont reproductibles.
