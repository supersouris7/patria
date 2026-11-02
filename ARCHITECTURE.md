# Architecture de Patria

Patria est une application web Java 21 **sans framework** : serveur HTTP du JDK, SQLite, HTML généré côté serveur.
Le code est découpé en **modules** (packages Java) qui ne dépendent les uns des autres que dans un seul sens.

## Carte des modules

```
fr.patria
│
├─ App, Config ............ assemble tous les modules et démarre le serveur (seul endroit où l'on fait « new »)
│
├─ portfolio ............... PATRIMOINE : vues de synthèse (vue d'ensemble, fiche d'un bien, mensualités, administration)
│   ├─ loan ................ PRÊTS : import CSV des échéanciers, calculs à date, pages d'un prêt
│   ├─ contribution ........ APPORTS : apports personnels d'un bien
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
| `loan`, `contribution` | idem + `property` |
| `portfolio` | tous les modules ci-dessus |

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
Navigateur ──► Router (web)
                 ├─ fichier statique ? → servi avec cache d'un an (versionné par empreinte)
                 ├─ route privée sans session → redirection /connexion
                 ├─ POST privé sans jeton CSRF valide → 400
                 └─ Controller.handle(Request) ──► Repository / calculs ──► Pages ──► Response
                                                   │
                 ValidationException → 400 · NotFoundException → 404 · autre → 500 journalisée
```

Le routeur applique ces règles **une seule fois** pour toutes les routes : un contrôleur n'a jamais à vérifier
la session ou le jeton CSRF, ni à construire une page d'erreur.

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

## Tests

| Niveau | Fichiers | Ce qui est vérifié |
|---|---|---|
| Calculs | `LoanMathTest`, `ValuationTest`, `CsvScheduleParserTest` | règles métier, sans base ni HTTP |
| Stockage | `PropertyRepositoryTest`, `MigrationsTest` | montants exacts, cascade, migration d'une ancienne base |
| Service | `PortfolioTest` | sommes de la vue d'ensemble |
| HTML | `HtmlTest` | échappement |
| Application | `AppTest` | vrai serveur HTTP : connexion, CSRF, parcours complet, 404 |
| Architecture | `ArchitectureTest` | sens des dépendances entre modules |

Les tests utilisent une horloge fixe (`TestData.CLOCK`) : les calculs « à date » sont reproductibles.
