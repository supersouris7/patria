# Architecture de Patria

Patria est une application web Java 21 **sans framework** : serveur HTTP du JDK, SQLite, HTML généré côté serveur.
Le code est découpé en **modules** (packages Java) qui ne dépendent les uns des autres que dans un seul sens.

## Carte des modules

```
fr.patria
│
├─ App, Config ............ assemble tous les modules et démarre le serveur (seul endroit où l'on fait « new »)
├─ HouseholdSite ........... un foyer : sa base SQLite, ses dépôts, ses contrôleurs et son routeur
│
├─ account ................. COMPTES : foyers, utilisateurs, droits (base comptes.sqlite3), Mon compte
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
- **Niveau de la route** (`Router.Access`) : `get`/`post` sont réservées à l'administrateur **par défaut** ;
  `userGet`/`userPost` ouvrent une page aux utilisateurs ; `estateGet` à ceux qui consultent l'immobilier.
- **Lignes** : sur une route ouverte, le contrôleur filtre avec `household/Visibility` (à partir de
  `Request.viewer()`) : ligne visible si un de ses membres a sa vue ouverte, modifiable si tous l'ont.
- **En-tête** : `Layout.CHROME` (posé par le routeur le temps de la requête) donne le nom, les foyers et les
  rubriques permises ; `Layout.isAdmin()` masque les actions réservées dans les pages partagées.

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
| Quelles API externes sont appelées ? | `market/DataGouvClient` (data.gouv.fr) et `market/HousingRates` (BCE), sans clé |
| Comment est construit le prévisionnel du foyer ? | `budget/Forecast` (mois par mois, endettement, alertes, épargne de précaution) |
| Comment est calculée la part d'une personne ? | `household/Shares.weight` (vide = parts égales), appliquée par `Forecast.Scope` ; table `shares` (migration v8) |
| Quel format CSV pour importer des dépenses ? | `budget/ExpenseCsv` (lecture, modèle), enregistrement en une transaction par `BudgetRepository.insertAll` |
| Comment comptent les virements et le compte joint ? | `household/Transfer`, lignes ajoutées par `Forecast.month` (vue d'un membre seulement) ; crédits du compte joint dans `Forecast.jointCredits` ; membre de type compte : `Person.Kind` (migration v9) |
| Où mènent les retours (après connexion, après enregistrement depuis le budget) ? | `web/LocalPath.safe` : adresse interne uniquement, sinon page par défaut |
| Quels mois pour la taxe foncière, la copropriété… dans le budget ? | `budget/Forecast.propertyCharges` ; taxe foncière mensualisée (10 prélèvements, janvier à octobre) : table `property_tax_monthly` (migration v10), choisie dans les charges du bien ; payeur de la taxe foncière (compte joint, personne) : table `property_tax_payers` (migration v11), choisi dans Administration → Répartition, appliqué par `Forecast.month` |
| Qui voit quoi (foyers, vues, immobilier) ? | `account/AccountRepository` (tables `households`, `users`, `user_households`, `user_members`), relu par `App.viewer` ; appliqué par `web/Router` (niveau de route) et `household/Visibility` (lignes) |
| Comment un bien change-t-il de foyer ? | `PropertyMove` (copie par ATTACH dans la base d'arrivée, nouveaux identifiants, puis suppression au départ), route `/administration/bien/{id}/foyer` déclarée dans `App` |
| Où est la base d'un foyer ? | `App.Sites` : foyer principal = DATABASE_PATH, les autres `foyer-N.sqlite3` à côté ; ouverte et migrée à la première visite |
| D'où vient le taux du marché affiché à côté des prêts ? | `market/HousingRates` (BCE, série MIR France, taux fixe plus de 10 ans) ; comparaison et repère de renégociation calculés par `loan/Renegotiation` (fonctions pures) |
| Comment sont produites les synthèses PDF ? | `ui/PdfReport` (mise en page A4 commune, OpenPDF, polices standard) ; contenu par `budget/BudgetReports`, `portfolio/PortfolioReports`, `loan/LoanReports` ; routes `*.pdf` dans les contrôleurs de chaque page |
| Comment est dessiné le graphique ? | `budget/BudgetChart` (SVG côté serveur, palette validée dans `patria.css`) |
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

Seul le module `market` appelle l'extérieur : `MarketData` (implémentée par `DataGouvClient`, data.gouv.fr) et
`HousingRates` (taux moyen des crédits immobiliers, API publique de la BCE, gardé en mémoire et rafraîchi chaque jour,
partagé par tous les foyers).
Ces appels ne bloquent jamais une page : le calcul est lancé en arrière-plan par `MarketService`, la fiche du bien
affiche l'ancien résultat ou « calcul en cours » et se recharge quand il est terminé (`app.js`). La vue d'ensemble
lit seulement le résultat en base. Une panne de data.gouv.fr n'efface pas un résultat valable ; l'indice
départemental (API lente) est facultatif : sans lui, le prix est affiché « non actualisé » et recalculé le lendemain.

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
| Comptes | `AccountsTest` | vrai serveur HTTP : vues ouvertes, lecture seule, pages réservées, mot de passe, foyers séparés |
| Architecture | `ArchitectureTest` | sens des dépendances entre modules |

Les tests utilisent une horloge fixe (`TestData.CLOCK`) : les calculs « à date » sont reproductibles.
